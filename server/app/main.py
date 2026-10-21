"""LAN API in front of Ollama. Tokens stream to the phone as SSE."""

from __future__ import annotations

import asyncio
import contextlib
import errno
import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any, Literal

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from app.addresses import banner_lines
from app.auth import require_api_key
from app.config import Settings, clean_model
from app.file_routes import attach_chat_file, register_file_routes
from app.files import FileAccessError, FileService
from app.messages import chat_stopped_message, missing_model_message, unreachable_message
from app.ollama import (
    HttpxOllama,
    ModelMissing,
    OllamaError,
    OllamaUnreachable,
    build_chat_body,
    build_warm_body,
)
from app.state import ModelStore
from app.model_catalog import ModelDownloads, register_model_routes
from app.workspace import WorkspaceStore, register_workspace_routes

log = logging.getLogger("desk")
SERVER_VERSION = "2.3.0"
CAPABILITIES = ("workspace_chat", "workspace_apply", "model_installs")


class HealthOut(BaseModel):
    server_version: str = SERVER_VERSION
    capabilities: list[str] = Field(default_factory=lambda: list(CAPABILITIES))
    status: str
    ollama: str
    ollama_version: str | None = None
    model: str
    model_loaded: bool
    detail: str | None = None


class ModelOut(BaseModel):
    name: str
    size_bytes: int | None = None
    parameter_size: str | None = None
    quantization: str | None = None
    loaded: bool = False


class ModelsOut(BaseModel):
    selected: str
    models: list[ModelOut]


class ModelIn(BaseModel):
    model: str


class ModelStateOut(BaseModel):
    model: str
    keep_alive: int | str
    loaded: bool
    warming: bool = False
    num_ctx: int


class MessageIn(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=100_000)


class ChatIn(BaseModel):
    model: str | None = None
    messages: list[MessageIn] = Field(min_length=1, max_length=100)
    file: str | None = None


def sse(payload: dict[str, Any]) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}\n\n"


def _model_loaded(selected: str, running: list[str]) -> bool:
    return selected in running


def _public_model(item: dict[str, Any], running: list[str]) -> ModelOut | None:
    name = item.get("name")
    if not isinstance(name, str) or not name:
        return None
    details = item.get("details") if isinstance(item.get("details"), dict) else {}
    size = item.get("size")
    parameter = details.get("parameter_size") if isinstance(details, dict) else None
    quant = details.get("quantization_level") if isinstance(details, dict) else None
    return ModelOut(
        name=name,
        size_bytes=size if isinstance(size, int) else None,
        parameter_size=parameter if isinstance(parameter, str) else None,
        quantization=quant if isinstance(quant, str) else None,
        loaded=_model_loaded(name, running),
    )


async def _health(app: FastAPI) -> HealthOut:
    settings: Settings = app.state.settings
    model = app.state.models.selected
    ollama = app.state.ollama
    try:
        async with asyncio.timeout(6.0):
            version, installed, running = await asyncio.gather(
                ollama.version(), ollama.tags(), ollama.running_names(),
            )
    except (OllamaUnreachable, TimeoutError):
        return HealthOut(
            status="degraded",
            ollama="down",
            model=model,
            model_loaded=False,
            detail=unreachable_message(settings.ollama_base_url),
        )
    except OllamaError as exc:
        return HealthOut(
            status="degraded",
            ollama="down",
            model=model,
            model_loaded=False,
            detail=str(exc),
        )
    names = [item.get("name") for item in installed if isinstance(item.get("name"), str)]
    if model not in names:
        return HealthOut(
            status="degraded",
            ollama="up",
            ollama_version=version,
            model=model,
            model_loaded=False,
            detail=missing_model_message(model),
        )
    return HealthOut(
        status="ok",
        ollama="up",
        ollama_version=version,
        model=model,
        model_loaded=_model_loaded(model, running),
        detail=None,
    )


async def warm_selected(app: FastAPI) -> None:
    settings: Settings = app.state.settings
    model = app.state.models.selected
    log.info("Loading %s into memory (keep_alive=%s)", model, settings.keep_alive)
    try:
        await app.state.ollama.warm(build_warm_body(model, settings.keep_alive))
    except ModelMissing as exc:
        log.warning("%s", exc)
    except OllamaUnreachable as exc:
        log.warning("%s", exc)
    except Exception:
        log.exception("Could not load %s", model)
    else:
        log.info("%s is loaded and will stay warm", model)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings
    model = app.state.models.selected
    print(
        "\n".join(
            banner_lines(
                settings.host,
                settings.port,
                settings.env_path,
                model,
                files_all_drives=settings.allowed_roots == ("ALL",),
            )
        ),
        flush=True,
    )
    task: asyncio.Task[None] | None = None
    if settings.warm_on_start:
        task = asyncio.create_task(warm_selected(app))
    app.state.warm_task = task
    try:
        yield
    finally:
        await app.state.downloads.close()
        current_task = app.state.warm_task
        for pending in {task, current_task} - {None}:
            pending.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await pending
        await app.state.ollama.aclose()


def create_app(
    settings: Settings | None = None,
    ollama: Any | None = None,
    file_service: FileService | None = None,
) -> FastAPI:
    settings = settings or Settings.load()
    if ollama is None:
        ollama = HttpxOllama(
            settings.ollama_base_url,
            connect_timeout=settings.connect_timeout,
            read_timeout=settings.read_timeout,
        )
    app = FastAPI(
        title="Home Desk",
        version=SERVER_VERSION,
        description="Streams a local Ollama model to the Desk phone app.",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.ollama = ollama
    app.state.downloads = ModelDownloads(ollama)
    register_model_routes(app)
    app.state.models = ModelStore(settings.state_path, settings.default_model)
    app.state.warm_task = None
    app.state.workspaces = WorkspaceStore()
    app.state.files = file_service or FileService(
        allowed_roots=settings.allowed_roots,
        max_upload_bytes=settings.max_upload_bytes,
        max_text_bytes=settings.max_text_bytes,
    )

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, __: RequestValidationError) -> JSONResponse:
        if request.url.path == "/chat":
            detail = "The chat request was missing a message."
        else:
            detail = "The request was missing something Desk needs."
        return JSONResponse(status_code=422, content={"detail": detail})

    @app.get("/")
    async def root() -> dict[str, Any]:
        return {
            "service": "home-desk",
            "ok": "true",
            "server_version": SERVER_VERSION,
            "capabilities": list(CAPABILITIES),
            "hint": "This is the Desk server. In the phone app, enter this URL and the API key from config/.env.",
        }

    @app.get("/health", response_model=HealthOut, dependencies=[Depends(require_api_key)])
    async def health(request: Request) -> HealthOut:
        return await _health(request.app)

    @app.get("/models", response_model=ModelsOut, dependencies=[Depends(require_api_key)])
    async def list_models(request: Request) -> ModelsOut:
        ollama_client = request.app.state.ollama
        try:
            raw_models = await ollama_client.tags()
            running = await ollama_client.running_names()
        except OllamaUnreachable as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        except OllamaError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        models = [item for item in (_public_model(raw, running) for raw in raw_models) if item is not None]
        return ModelsOut(selected=request.app.state.models.selected, models=models)

    @app.get("/model", response_model=ModelStateOut, dependencies=[Depends(require_api_key)])
    async def get_model(request: Request) -> ModelStateOut:
        settings_now: Settings = request.app.state.settings
        model = request.app.state.models.selected
        loaded = False
        try:
            loaded = _model_loaded(model, await request.app.state.ollama.running_names())
        except (OllamaUnreachable, OllamaError):
            loaded = False
        return ModelStateOut(
            model=model,
            keep_alive=settings_now.keep_alive,
            loaded=loaded,
            num_ctx=settings_now.num_ctx,
        )

    @app.put("/model", response_model=ModelStateOut, dependencies=[Depends(require_api_key)])
    async def select_model(body: ModelIn, request: Request) -> ModelStateOut:
        try:
            model = clean_model(body.model)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        request.app.state.models.set(model)
        settings_now: Settings = request.app.state.settings
        # Refresh keep_alive and load weights without making the phone wait.
        previous_warm = request.app.state.warm_task
        if previous_warm is not None and not previous_warm.done():
            previous_warm.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await previous_warm
        request.app.state.warm_task = asyncio.create_task(warm_selected(request.app))
        await asyncio.sleep(0)
        loaded = False
        try:
            loaded = _model_loaded(model, await request.app.state.ollama.running_names())
        except (OllamaUnreachable, OllamaError):
            loaded = False
        return ModelStateOut(
            model=model,
            keep_alive=settings_now.keep_alive,
            loaded=loaded,
            warming=True,
            num_ctx=settings_now.num_ctx,
        )

    @app.post("/chat", dependencies=[Depends(require_api_key)])
    async def chat(body: ChatIn, request: Request) -> StreamingResponse:
        settings_now: Settings = request.app.state.settings
        if body.model:
            try:
                model = clean_model(body.model)
            except ValueError as exc:
                raise HTTPException(status_code=400, detail=str(exc)) from exc
        else:
            model = request.app.state.models.selected
        messages = [{"role": item.role, "content": item.content} for item in body.messages]
        context_tokens = settings_now.num_ctx
        attached = (body.file or "").strip()
        if attached:
            try:
                messages, context_tokens = attach_chat_file(
                    request.app.state.files,
                    attached,
                    messages,
                    settings_now.num_ctx,
                )
            except FileAccessError as exc:
                raise HTTPException(status_code=exc.status, detail=str(exc)) from exc
        payload = build_chat_body(
            model,
            messages,
            settings_now.keep_alive,
            context_tokens,
            settings_now.temperature,
        )
        log.info("chat model=%s messages=%d", model, len(messages))

        async def events() -> AsyncIterator[str]:
            try:
                async for chunk in request.app.state.ollama.stream_chat(payload):
                    if await request.is_disconnected():
                        break
                    content = ""
                    message = chunk.get("message")
                    if isinstance(message, dict) and isinstance(message.get("content"), str):
                        content = message["content"]
                    if content:
                        yield sse({"content": content})
                    if chunk.get("done"):
                        yield sse({"done": True, "model": model})
                        return
            except ModelMissing as exc:
                yield sse({"error": str(exc)})
            except OllamaUnreachable as exc:
                yield sse({"error": str(exc)})
            except OllamaError as exc:
                yield sse({"error": str(exc)})
            except Exception:
                log.exception("chat failed")
                yield sse({"error": chat_stopped_message()})

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    register_file_routes(app)
    register_workspace_routes(app)
    return app


app = create_app()


def main() -> None:
    settings: Settings = app.state.settings
    try:
        uvicorn.run(
            app,
            host=settings.host,
            port=settings.port,
            timeout_keep_alive=300,
            log_level="info",
            proxy_headers=False,
            server_header=False,
        )
    except OSError as exc:
        if exc.errno == errno.EADDRINUSE:
            raise SystemExit(
                f"Port {settings.port} is already in use. Close the other Desk window, "
                "or change PORT in config/.env."
            ) from exc
        raise


if __name__ == "__main__":
    main()

