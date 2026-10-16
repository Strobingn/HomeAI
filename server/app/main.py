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

log = logging.getLogger("desk")


class HealthOut(BaseModel):
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
        version = await ollama.version()
        installed = await ollama.tags()
        running = await ollama.running_names()
    except OllamaUnreachable:
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
    print("\n".join(banner_lines(settings.host, settings.port, settings.env_path, model)), flush=True)
    task: asyncio.Task[None] | None = None
    if settings.warm_on_start:
        task = asyncio.create_task(warm_selected(app))
    app.state.warm_task = task
    try:
        yield
    finally:
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        await app.state.ollama.aclose()


def create_app(settings: Settings | None = None, ollama: Any | None = None) -> FastAPI:
    settings = settings or Settings.load()
    if ollama is None:
        ollama = HttpxOllama(
            settings.ollama_base_url,
            connect_timeout=settings.connect_timeout,
            read_timeout=settings.read_timeout,
        )
    app = FastAPI(
        title="Home Desk",
        version="1.0.0",
        description="Streams a local Ollama model to the Desk phone app.",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.ollama = ollama
    app.state.models = ModelStore(settings.state_path, settings.default_model)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_: Request, __: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={"detail": "The chat request was missing a message."},
        )

    @app.get("/")
    async def root() -> dict[str, str]:
        return {
            "service": "home-desk",
            "ok": "true",
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
        payload = build_chat_body(
            model,
            messages,
            settings_now.keep_alive,
            settings_now.num_ctx,
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
