"""Install, load and select a 12 GB coding model, then enable verified web search."""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import replace
import json
import sys
import time

import httpx

from app.config import DEFAULT_WEB_CODER_MODEL, Settings
from app.ensure_config import upsert_setting, write_private_env
from app.ollama import HttpxOllama, OllamaError, OllamaUnreachable, build_chat_body, build_warm_body
from app.setup_coder import configure
from app.web_search import WebSearch, WebSearchError

MODEL = DEFAULT_WEB_CODER_MODEL


async def install(settings: Settings) -> None:
    # Fail before a large download if search is unavailable, rather than promise a
    # web-capable model when only its weights were installed.
    web = WebSearch(replace(settings, web_search_enabled=True))
    print(f"Testing live search ({settings.web_search_provider})...", flush=True)
    result = await web.search("Python official documentation")
    print(f"Live search returned {len(result.results)} sources.", flush=True)
    ollama = HttpxOllama(settings.ollama_base_url)
    try:
        print(f"Downloading {MODEL} (~6.6 GB, Q4_K_M). Cached layers resume automatically.", flush=True)
        last = 0.0
        async for chunk in ollama.pull_model(MODEL):
            now = time.monotonic()
            if now - last >= 1 or chunk.get("status") == "success":
                line = str(chunk.get("status", "Downloading"))
                total, completed = chunk.get("total"), chunk.get("completed")
                if isinstance(total, int) and total > 0 and isinstance(completed, int):
                    line += f" · layer {min(100, completed * 100 // total)}%"
                print(line, flush=True)
                last = now
        if MODEL.casefold() not in {str(row.get("name", "")).casefold() for row in await ollama.tags()}:
            raise OllamaError("Ollama did not list the downloaded model. Update Ollama and rerun setup.")
        for previous in await ollama.running_names():
            if previous.casefold() != MODEL.casefold():
                await ollama.warm(build_warm_body(previous, 0))
        body = build_warm_body(MODEL, settings.keep_alive)
        body["options"] = {"num_ctx": 8192}
        print("Loading the model at 8192 context tokens...", flush=True)
        await ollama.warm(body)
        prompt = build_chat_body(MODEL, [{"role": "user", "content": "Write a Python function that returns the sum of two numbers. No explanation."}], settings.keep_alive, 8192, 0.2)
        prompt["options"]["num_predict"] = 128
        answer = []
        done = False
        async with asyncio.timeout(120):
            async for chunk in ollama.stream_chat(prompt):
                message = chunk.get("message", {})
                if isinstance(message, dict) and isinstance(message.get("content"), str):
                    answer.append(message["content"])
                if chunk.get("done"):
                    done = True
                    break
        if not done or not "".join(answer).strip():
            raise OllamaError("The model did not complete a test reply. Existing HomeAI configuration was preserved.")
        loaded = next((row for row in await ollama.running_models() if str(row.get("name", row.get("model", ""))).casefold() == MODEL.casefold()), None)
        if loaded is None:
            raise OllamaError("The model was not visible in Ollama's loaded-model list. Existing selection was preserved.")
        size, vram = loaded.get("size", 0), loaded.get("size_vram", 0)
        if isinstance(size, int) and size > 0 and isinstance(vram, int):
            print(f"Ollama reports {vram / 1024**3:.2f} GiB in GPU memory of {size / 1024**3:.2f} GiB total.", flush=True)
            if vram < size * 0.95:
                print("This load is partly on CPU. Close other GPU workloads and check ollama ps; full GPU residency is not confirmed.", flush=True)
        configure(settings, MODEL)
        text = settings.env_path.read_text(encoding="utf-8")
        for key, value in (("WEB_SEARCH_ENABLED", "1"), ("WEB_SEARCH_PROVIDER", settings.web_search_provider), ("NUM_CTX", "8192")):
            text = upsert_setting(text, key, value)
        write_private_env(settings.env_path, text)
        marker = settings.env_path.with_name("web-coder-setup.json")
        temporary = marker.with_suffix(".json.tmp")
        temporary.write_text(json.dumps({"model": MODEL, "provider": settings.web_search_provider, "verified": True}) + "\n", encoding="utf-8")
        temporary.replace(marker)
        print("Model downloaded, visible in Ollama, loaded and tested. Selected for HomeAI chat and file coding.", flush=True)
        print("Web search passed. Starting the gateway will load these settings. On the phone: Settings > Test connection & sync model.", flush=True)
        print("In normal or folder chat, type: /web Search the Python docs and write an example", flush=True)
    finally:
        await ollama.aclose()


async def verify_server(settings: Settings) -> None:
    async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{settings.port}", timeout=25, trust_env=False,
                                 headers={"Authorization": f"Bearer {settings.api_key}"}) as client:
        health = await client.get("/health")
        health.raise_for_status()
        status = await client.get("/web/status")
        status.raise_for_status()
        if health.json().get("model") != MODEL or health.json().get("status") != "ok" or not status.json().get("ready"):
            raise OllamaError("The running gateway has not adopted the verified model/search setup. Restart it from this updated checkout.")
        search = await client.post("/web/search", json={"query": "Python official documentation"})
        search.raise_for_status()
        if not search.json().get("results"):
            raise OllamaError("The running gateway did not return web search results.")
    print("Confirmed: the running HomeAI gateway sees the selected model and returns live web results.", flush=True)


async def install_when_stopped(settings: Settings) -> None:
    async with httpx.AsyncClient(timeout=1, trust_env=False) as client:
        try:
            await client.get(f"http://127.0.0.1:{settings.port}/")
        except httpx.HTTPError:
            pass
        else:
            raise OllamaError(f"Port {settings.port} is already answering. Close the old Desk gateway window before running setup, so it can restart with the new model and search settings.")
    await install(settings)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-server", action="store_true", help="Check the running gateway without changing or downloading anything.")
    args = parser.parse_args()
    try:
        settings = Settings.load()
        asyncio.run(verify_server(settings) if args.verify_server else install_when_stopped(settings))
    except (OllamaError, OllamaUnreachable, WebSearchError, httpx.HTTPError, OSError, TimeoutError) as exc:
        print(f"Web coding setup did not finish: {exc}", file=sys.stderr, flush=True)
        print("Open/update Ollama and retry. Search providers can block requests; the README describes the optional Ollama search provider.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Stopped. Run setup again to resume cached model layers.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
