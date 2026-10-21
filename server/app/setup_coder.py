"""Install the chosen local coding model on the PC running this gateway."""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

from app.config import DEFAULT_CODER_MODEL, Settings
from app.ensure_config import upsert_setting
from app.ollama import HttpxOllama, OllamaError, OllamaUnreachable, build_warm_body
from app.state import ModelStore

RECOMMENDED_MODEL = DEFAULT_CODER_MODEL


def configure(settings: Settings, model: str) -> None:
    text = settings.env_path.read_text(encoding="utf-8") if settings.env_path.exists() else ""
    text = upsert_setting(upsert_setting(text, "CODER_MODEL", model), "DEFAULT_MODEL", model)
    temporary = settings.env_path.with_name(settings.env_path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(settings.env_path)
    ModelStore(settings.state_path, settings.default_model).set(model)


async def install(settings: Settings, *, if_needed: bool = False) -> None:
    marker: Path = settings.env_path.with_name("coder-setup.json")
    ollama = HttpxOllama(settings.ollama_base_url)
    try:
        installed = {str(row.get("name", "")).casefold() for row in await ollama.tags()}
        try:
            recorded = json.loads(marker.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            recorded = None
        if if_needed and recorded == {"model": RECOMMENDED_MODEL} and RECOMMENDED_MODEL.casefold() in installed:
            print("Coding model setup is complete. Keeping the model you selected in Desk.", flush=True)
            return
        print(f"Installing {RECOMMENDED_MODEL} on this PC through Ollama. Download is about 6.6 GB.", flush=True)
        last_print = 0.0
        async for chunk in ollama.pull_model(RECOMMENDED_MODEL):
            now = time.monotonic()
            if now - last_print >= 1 or chunk.get("status") == "success":
                detail = str(chunk.get("status", "Downloading"))
                total, completed = chunk.get("total"), chunk.get("completed")
                if isinstance(total, int) and total > 0 and isinstance(completed, int):
                    detail += f" {min(100, completed * 100 // total)}%"
                print(detail, flush=True)
                last_print = now
        installed = {str(row.get("name", "")).casefold() for row in await ollama.tags()}
        if RECOMMENDED_MODEL.casefold() not in installed:
            raise OllamaError("The download ended but the model is not listed in Ollama. Update Ollama and rerun setup.")
        for previous in await ollama.running_names():
            if previous.casefold() != RECOMMENDED_MODEL.casefold():
                await ollama.warm(build_warm_body(previous, 0))
        print("Loading the coding model before selecting it...", flush=True)
        body = build_warm_body(RECOMMENDED_MODEL, settings.keep_alive)
        body["options"] = {"num_ctx": max(8192, settings.num_ctx)}
        await ollama.warm(body)
        configure(settings, RECOMMENDED_MODEL)
        temporary = marker.with_suffix(".json.tmp")
        temporary.write_text(json.dumps({"model": RECOMMENDED_MODEL}) + "\n", encoding="utf-8")
        temporary.replace(marker)
        print(f"Installed and selected {RECOMMENDED_MODEL} for Desk and file coding. Thinking is disabled for faster replies.", flush=True)
        print("On the phone, use Settings > Test connection to sync the model. Run ollama ps on the PC to check GPU usage.", flush=True)
    finally:
        await ollama.aclose()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--if-needed", action="store_true", help="Skip an already completed setup without changing later selections.")
    args = parser.parse_args()
    try:
        asyncio.run(install(Settings.load(), if_needed=args.if_needed))
    except (OllamaError, OllamaUnreachable, OSError) as exc:
        print(f"Coding model setup did not finish: {exc}", file=sys.stderr, flush=True)
        print("Update Ollama from https://ollama.com/download, open it, and restart Desk to resume setup.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Setup interrupted; downloaded Ollama layers can be resumed next time.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
