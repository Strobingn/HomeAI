"""File routes. Every one of them requires the same API key as chat."""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field

from app.auth import require_api_key
from app.config import Settings
from app.file_ai import (
    attachment_message,
    build_file_messages,
    extract_proposed,
    model_budget,
    select_file_model,
    too_large_for_model,
)
from app.files import FileAccessError, FileService
from app.messages import chat_stopped_message
from app.ollama import ModelMissing, OllamaError, OllamaUnreachable, build_chat_body

log = logging.getLogger("desk")


class WriteIn(BaseModel):
    path: str
    content: str


class FolderIn(BaseModel):
    path: str


class RenameIn(BaseModel):
    path: str
    name: str = Field(min_length=1, max_length=180)


class DeleteIn(BaseModel):
    path: str


class FileAiIn(BaseModel):
    path: str
    instruction: str = Field(min_length=1, max_length=20_000)
    model: str | None = None


def sse(payload: dict[str, Any]) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}\n\n"


def _raise(exc: FileAccessError) -> None:
    raise HTTPException(status_code=exc.status, detail=str(exc)) from exc


def register_file_routes(app: Any) -> None:
    router = APIRouter(dependencies=[Depends(require_api_key)])

    @router.get("/files/roots")
    async def list_roots(request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            return service.roots_payload()
        except FileAccessError as exc:
            _raise(exc)

    @router.get("/files/list")
    async def list_dir(path: str, request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            return service.list_dir(path)
        except FileAccessError as exc:
            _raise(exc)

    @router.get("/files/read")
    async def read_file(path: str, request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            document = service.read_text(path)
        except FileAccessError as exc:
            _raise(exc)
        return {
            "path": str(document.path),
            "name": document.name,
            "content": document.content,
            "size": document.size,
        }

    @router.get("/files/download")
    async def download_file(path: str, request: Request) -> FileResponse:
        service: FileService = request.app.state.files
        try:
            target = service.file_for_download(path)
        except FileAccessError as exc:
            _raise(exc)
        return FileResponse(
            target,
            filename=target.name,
            content_disposition_type="attachment",
            headers={"Cache-Control": "no-store"},
        )

    @router.post("/files/upload")
    async def upload_file(
        request: Request,
        directory: str = Form(),
        file: list[UploadFile] = File(),
    ) -> dict[str, Any]:
        service: FileService = request.app.state.files
        saved: list[dict[str, Any]] = []
        try:
            if not file:
                raise HTTPException(status_code=400, detail="Choose a file to upload.")
            for item in file:
                saved.append(
                    await service.save_upload(directory, item.filename or "upload", _chunks(item))
                )
        except FileAccessError as exc:
            _raise(exc)
        finally:
            for item in file:
                await item.close()
        log.info("upload count=%d directory=%s", len(saved), directory)
        return {"saved": saved}

    @router.post("/files/folder")
    async def make_folder(body: FolderIn, request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            created = service.make_dir(body.path)
        except FileAccessError as exc:
            _raise(exc)
        return created

    @router.put("/files/write")
    async def write_file(body: WriteIn, request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            saved = service.write_text(body.path, body.content)
        except FileAccessError as exc:
            _raise(exc)
        log.info("write path=%s bytes=%s backup=%s", saved.get("path"), saved.get("bytes"), saved.get("backup"))
        return saved

    @router.post("/files/rename")
    async def rename_file(body: RenameIn, request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            return service.rename(body.path, body.name)
        except FileAccessError as exc:
            _raise(exc)

    @router.post("/files/delete")
    async def delete_file(body: DeleteIn, request: Request) -> dict[str, Any]:
        service: FileService = request.app.state.files
        try:
            removed = service.delete(body.path)
        except FileAccessError as exc:
            _raise(exc)
        log.info("trash path=%s dest=%s", removed.get("path"), removed.get("trashed"))
        return removed

    @router.post("/files/ai")
    async def edit_with_model(body: FileAiIn, request: Request) -> StreamingResponse:
        service: FileService = request.app.state.files
        settings: Settings = request.app.state.settings
        try:
            document = service.read_text(body.path)
            chosen_ctx, limit = model_budget(document.content, settings.num_ctx)
            if len(document.content) > limit:
                raise FileAccessError(too_large_for_model(limit), 413)
            model = await select_file_model(
                request.app.state.ollama,
                document.path,
                body.model,
                settings,
            )
        except FileAccessError as exc:
            _raise(exc)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        messages = build_file_messages(document.path, document.content, body.instruction)
        payload = build_chat_body(
            model,
            messages,
            settings.keep_alive,
            chosen_ctx,
            min(settings.temperature, 0.2),
        )
        log.info("file-ai model=%s path=%s", model, document.path)

        async def events() -> AsyncIterator[str]:
            parts: list[str] = []
            try:
                async for chunk in request.app.state.ollama.stream_chat(payload):
                    if await request.is_disconnected():
                        break
                    content = ""
                    message = chunk.get("message")
                    if isinstance(message, dict) and isinstance(message.get("content"), str):
                        content = message["content"]
                    if content:
                        parts.append(content)
                        yield sse({"content": content})
                    if chunk.get("done"):
                        proposed = extract_proposed("".join(parts))
                        yield sse({"done": True, "model": model, "proposed": proposed})
                        return
            except ModelMissing as exc:
                yield sse({"error": str(exc)})
            except OllamaUnreachable as exc:
                yield sse({"error": str(exc)})
            except OllamaError as exc:
                yield sse({"error": str(exc)})
            except Exception:
                log.exception("file-ai failed")
                yield sse({"error": chat_stopped_message()})

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    app.include_router(router)


async def _chunks(upload: UploadFile) -> AsyncIterator[bytes]:
    while True:
        data = await upload.read(1024 * 1024)
        if not data:
            break
        yield data


def attach_chat_file(
    service: FileService,
    path: str,
    messages: list[dict[str, str]],
    num_ctx: int,
) -> tuple[list[dict[str, str]], int]:
    document = service.read_text(path)
    chosen, limit = model_budget(document.content, num_ctx)
    if len(document.content) > limit:
        raise FileAccessError(too_large_for_model(limit), 413)
    return [attachment_message(document), *messages], chosen
