"""Curated local models and gateway-owned, resumable Ollama downloads.

Only these registry tags can be pulled over the LAN API. Installing does not
change the selected chat/coding model or load several models into GPU memory.
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import logging
import uuid
from typing import Any

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.auth import require_api_key
from app.config import Settings
from app.ollama import HttpxOllama, OllamaError, OllamaUnreachable

log = logging.getLogger("desk.models")
CATALOG = [
    dict(model="ornith-1.5:9b", name="Ornith 1.5 · 9B", category="Coding", download_gb=6.6,
         description="Coding and project work. Balanced choice for a 12 GB GPU.",
         source="https://ollama.com/library/ornith-1.5:9b", uncensored=False),
    dict(model="qwen3.5:4b-q4_K_M", name="Qwen 3.5 · 4B", category="Fast", download_gb=3.3,
         description="Smaller coding and everyday model with more memory headroom.",
         source="https://ollama.com/library/qwen3.5:4b-q4_K_M", uncensored=False),
    dict(model="huihui_ai/qwen3.5-abliterated:4B", name="Qwen 3.5 Abliterated · 4B", category="Uncensored", download_gb=3.3,
         description="Publisher's refusal-reduced Qwen variant. Smaller, fast option.",
         source="https://ollama.com/huihui_ai/qwen3.5-abliterated:4B", uncensored=True),
    dict(model="huihui_ai/qwen3.5-abliterated:9b", name="Qwen 3.5 Abliterated · 9B", category="Uncensored", download_gb=6.6,
         description="Publisher's refusal-reduced Qwen variant for coding and chat.",
         source="https://ollama.com/huihui_ai/qwen3.5-abliterated:9b", uncensored=True),
    dict(model="dolphin3:8b", name="Dolphin 3 · 8B", category="Uncensored", download_gb=4.9,
         description="Steerable general chat, coding and math from the Dolphin series.",
         source="https://ollama.com/library/dolphin3", uncensored=True),
]
ALLOWED_MODELS = {row["model"] for row in CATALOG}
UNCENSORED_PACK = ["huihui_ai/qwen3.5-abliterated:9b", "dolphin3:8b"]


class InstallIn(BaseModel):
    models: list[str] = Field(min_length=1, max_length=len(CATALOG))


class CancelIn(BaseModel):
    id: str = Field(min_length=1, max_length=64)


class ModelDownloads:
    def __init__(self, ollama: Any):
        self.ollama = ollama
        self.task: asyncio.Task[None] | None = None
        self.state: dict[str, Any] = {"phase": "idle", "models": [], "finished": []}

    @property
    def active(self) -> bool:
        return self.task is not None and not self.task.done()

    def start(self, models: list[str]) -> dict[str, Any]:
        models = list(dict.fromkeys(models))
        if not models or any(model not in ALLOWED_MODELS for model in models):
            raise ValueError("Choose a model from the Desk catalog.")
        if self.active:
            raise RuntimeError("A model download is already running. Wait or stop it first.")
        self.state = dict(id=uuid.uuid4().hex, phase="downloading", models=models,
                          finished=[], model=models[0], status="Checking installed models", completed=0, total=0)
        self.task = asyncio.create_task(self._run(models))
        return dict(self.state)

    async def _run(self, models: list[str]) -> None:
        try:
            installed = {str(row.get("name", "")).casefold() for row in await self.ollama.tags()}
            for model in models:
                self.state.update(model=model, completed=0, total=0, status="Starting download")
                if model.casefold() not in installed:
                    async for chunk in self.ollama.pull_model(model):
                        total = chunk.get("total", 0)
                        completed = chunk.get("completed", 0)
                        self.state.update(
                            status=str(chunk.get("status", "Downloading"))[:300],
                            total=max(0, total) if isinstance(total, int) else 0,
                            completed=max(0, completed) if isinstance(completed, int) else 0,
                        )
                    names = {str(row.get("name", "")).casefold() for row in await self.ollama.tags()}
                    if model.casefold() not in names:
                        raise OllamaError(f"{model} was not found after downloading. Update Ollama and retry.")
                self.state["finished"] = [*self.state["finished"], model]
            self.state.update(phase="complete", status="Installed on your PC. Choose Use to switch models.", completed=0, total=0)
        except asyncio.CancelledError:
            self.state.update(phase="cancelled", status="Download stopped. Retry to resume downloaded layers.")
            raise
        except (OllamaError, OllamaUnreachable, OSError) as exc:
            self.state.update(phase="error", status=str(exc)[:1000])
        except Exception:
            log.exception("Model download failed")
            self.state.update(phase="error", status="Download failed. Check the Desk window on your PC and retry.")

    async def close(self) -> None:
        if self.active:
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.task
            if self.state["phase"] == "downloading":
                self.state.update(phase="cancelled", status="Download stopped. Retry to resume downloaded layers.")


def register_model_routes(app: FastAPI) -> None:
    auth = [Depends(require_api_key)]

    @app.get("/models/catalog", dependencies=auth)
    async def catalog() -> dict[str, Any]:
        return {"models": CATALOG, "uncensored_pack": UNCENSORED_PACK,
                "note": "Download sizes are approximate, not VRAM requirements. Run one model at a time; context also uses memory. Models do not include web access."}

    @app.get("/model-installs", dependencies=auth)
    async def install_status() -> dict[str, Any]:
        return dict(app.state.downloads.state)

    @app.post("/model-installs", status_code=202, dependencies=auth)
    async def install(body: InstallIn) -> dict[str, Any]:
        try:
            return app.state.downloads.start(body.models)
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(409, str(exc)) from exc

    @app.post("/model-installs/cancel", dependencies=auth)
    async def cancel(body: CancelIn) -> dict[str, Any]:
        if app.state.downloads.state.get("id") != body.id:
            raise HTTPException(409, "This download has changed. Refresh its status first.")
        await app.state.downloads.close()
        return dict(app.state.downloads.state)


async def install_pack() -> None:
    settings = Settings.load()
    ollama = HttpxOllama(settings.ollama_base_url)
    downloads = ModelDownloads(ollama)
    try:
        downloads.start(UNCENSORED_PACK)
        previous = None
        while downloads.active:
            state = downloads.state
            line = f"{state.get('model', '')}: {state.get('status', '')}"
            if state.get("total", 0) > 0:
                line += f" · layer {min(100, state['completed'] * 100 // state['total'])}%"
            if line != previous:
                print(line, flush=True)
                previous = line
            await asyncio.sleep(1)
        if downloads.state["phase"] != "complete":
            raise OllamaError(downloads.state["status"])
        print(downloads.state["status"], flush=True)
    finally:
        await downloads.close()
        await ollama.aclose()


def main() -> None:
    argparse.ArgumentParser(description="Install Qwen 3.5 Abliterated 9B and Dolphin 3 8B on this PC (about 11.5 GB). Existing models and selection are preserved.").parse_args()
    try:
        asyncio.run(install_pack())
    except (OllamaError, OllamaUnreachable, OSError) as exc:
        raise SystemExit(str(exc)) from exc


if __name__ == "__main__":
    main()
