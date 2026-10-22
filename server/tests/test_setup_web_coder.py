import asyncio
import json
from pathlib import Path

import pytest

from app import setup_web_coder, setup_coder
from app.ollama import OllamaError
from app.state import ModelStore
from app.web_search import WebSearchError
from tests.test_api import make_settings
from tests.test_web_search import result


class InstalledOllama:
    def __init__(self, fail=False):
        self.installed = False
        self.closed = False
        self.warms = []
        self.fail = fail

    async def pull_model(self, model):
        assert model == setup_web_coder.MODEL
        if self.fail:
            raise OllamaError("Download failed")
        self.installed = True
        yield {"status": "success"}

    async def tags(self):
        return [{"name": setup_web_coder.MODEL}] if self.installed else []

    async def running_names(self):
        return ["old:7b"]

    async def warm(self, body):
        self.warms.append(body)

    async def stream_chat(self, body):
        assert body["think"] is False
        assert body["options"]["num_ctx"] == 8192
        yield {"message": {"content": "def add(a, b): return a + b"}, "done": True}

    async def running_models(self):
        return [{"name": setup_web_coder.MODEL, "size": 7_000_000_000, "size_vram": 7_000_000_000}]

    async def aclose(self):
        self.closed = True


class GoodSearch:
    def __init__(self, settings):
        assert settings.web_search_enabled

    async def search(self, query):
        return result(query)


def test_pc_installer_verifies_then_persists_chat_coder_and_web_without_changing_key(tmp_path, monkeypatch):
    settings = make_settings(tmp_path)
    settings.env_path.write_text("API_KEY=keep-private\nDEFAULT_MODEL=old:7b\nCUSTOM_SETTING=keep\n")
    fake = InstalledOllama()
    monkeypatch.setattr(setup_web_coder, "HttpxOllama", lambda _: fake)
    monkeypatch.setattr(setup_web_coder, "WebSearch", GoodSearch)
    asyncio.run(setup_web_coder.install(settings))
    text = settings.env_path.read_text()
    assert "API_KEY=keep-private" in text and "CUSTOM_SETTING=keep" in text
    for key in ("DEFAULT_MODEL", "CODER_MODEL"):
        assert f"{key}={setup_web_coder.MODEL}" in text
    assert "WEB_SEARCH_ENABLED=1" in text and "NUM_CTX=8192" in text
    assert ModelStore(settings.state_path, "old").selected == setup_web_coder.MODEL
    assert json.loads((tmp_path / "web-coder-setup.json").read_text())["verified"]
    assert fake.warms[0]["keep_alive"] == 0
    assert fake.warms[-1]["options"]["num_ctx"] == 8192
    assert fake.closed
    if __import__("os").name != "nt":
        assert settings.env_path.stat().st_mode & 0o077 == 0


def test_failed_model_download_does_not_select_or_enable_web(tmp_path, monkeypatch):
    settings = make_settings(tmp_path)
    settings.env_path.write_text("API_KEY=keep-private\n")
    fake = InstalledOllama(fail=True)
    monkeypatch.setattr(setup_web_coder, "HttpxOllama", lambda _: fake)
    monkeypatch.setattr(setup_web_coder, "WebSearch", GoodSearch)
    with pytest.raises(OllamaError):
        asyncio.run(setup_web_coder.install(settings))
    assert settings.env_path.read_text() == "API_KEY=keep-private\n"
    assert not settings.state_path.exists()
    assert fake.closed


def test_ordinary_windows_start_keeps_the_verified_web_model_setup(tmp_path, monkeypatch):
    settings = make_settings(tmp_path)
    fake = InstalledOllama()
    fake.installed = True
    marker = settings.env_path.with_name("web-coder-setup.json")
    marker.write_text(json.dumps({"model": setup_web_coder.MODEL, "verified": True}))
    monkeypatch.setattr(setup_coder, "HttpxOllama", lambda _: fake)
    asyncio.run(setup_coder.install(settings, if_needed=True))
    assert not fake.warms
    assert fake.closed


def test_failed_search_does_not_start_a_large_download(tmp_path, monkeypatch):
    settings = make_settings(tmp_path)
    settings.env_path.write_text("API_KEY=keep-private\n")
    class BrokenSearch(GoodSearch):
        async def search(self, query):
            raise WebSearchError("Search unavailable")
    monkeypatch.setattr(setup_web_coder, "WebSearch", BrokenSearch)
    monkeypatch.setattr(setup_web_coder, "HttpxOllama", lambda _: pytest.fail("Must not download before search is verified"))
    with pytest.raises(WebSearchError):
        asyncio.run(setup_web_coder.install(settings))
    assert settings.env_path.read_text() == "API_KEY=keep-private\n"


def test_verifier_checks_the_running_gateway_model_and_search(tmp_path, monkeypatch):
    import httpx
    original = httpx.AsyncClient
    paths = []
    def handle(request):
        assert request.url.host == "127.0.0.1"
        assert request.headers["Authorization"] == "Bearer test-key"
        paths.append(request.url.path)
        data = {"/health": {"status": "ok", "model": setup_web_coder.MODEL},
                "/web/status": {"ready": True}, "/web/search": {"results": result().results}}
        return httpx.Response(200, json=data[request.url.path])
    monkeypatch.setattr(setup_web_coder.httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handle), **kwargs))
    asyncio.run(setup_web_coder.verify_server(make_settings(tmp_path)))
    assert paths == ["/health", "/web/status", "/web/search"]
