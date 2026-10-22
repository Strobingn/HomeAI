import asyncio
import json
from pathlib import Path

import httpx
import pytest

from app import setup_coder
from app.ollama import HttpxOllama, OllamaError, build_chat_body
from app.state import ModelStore
from tests.test_api import make_settings


class FakeDownload:
    def __init__(self, fail=False):
        self.installed = False
        self.fail = fail
        self.pulls = 0
        self.closed = False
        self.warms = []

    async def tags(self):
        return [{"name": setup_coder.RECOMMENDED_MODEL}] if self.installed else []

    async def pull_model(self, model):
        self.pulls += 1
        yield {"status": "pulling", "total": 100, "completed": 50}
        if self.fail:
            raise OllamaError("download failed")
        self.installed = True
        yield {"status": "success"}

    async def running_names(self):
        return ["old-model:7b"]

    async def warm(self, body):
        self.warms.append(body)

    async def aclose(self):
        self.closed = True


def test_installer_pulls_loads_then_selects_without_changing_api_key(tmp_path: Path, monkeypatch):
    settings = make_settings(tmp_path)
    settings.env_path.write_text("# keep this\nAPI_KEY=private-key\nDEFAULT_MODEL=qwen2.5:7b\nCODER_MODEL = old-model:14b\n")
    fake = FakeDownload()
    monkeypatch.setattr(setup_coder, "HttpxOllama", lambda _: fake)
    asyncio.run(setup_coder.install(settings, if_needed=True))
    assert fake.pulls == 1
    assert fake.closed
    assert fake.warms[0]["keep_alive"] == 0
    assert fake.warms[-1]["model"] == setup_coder.RECOMMENDED_MODEL
    assert fake.warms[-1]["options"]["num_ctx"] >= 8192
    text = settings.env_path.read_text()
    assert "API_KEY=private-key" in text
    assert "# keep this" in text
    assert f"DEFAULT_MODEL={setup_coder.RECOMMENDED_MODEL}" in text
    assert f"CODER_MODEL={setup_coder.RECOMMENDED_MODEL}" in text
    assert ModelStore(settings.state_path, "fallback").selected == setup_coder.RECOMMENDED_MODEL
    ModelStore(settings.state_path, "fallback").set("user-choice:7b")
    asyncio.run(setup_coder.install(settings, if_needed=True))
    assert fake.pulls == 1
    assert ModelStore(settings.state_path, "fallback").selected == "user-choice:7b"


def test_failed_download_does_not_select_or_mark_the_model_ready(tmp_path: Path, monkeypatch):
    settings = make_settings(tmp_path)
    settings.env_path.write_text("API_KEY=private-key\n")
    fake = FakeDownload(fail=True)
    monkeypatch.setattr(setup_coder, "HttpxOllama", lambda _: fake)
    with pytest.raises(OllamaError):
        asyncio.run(setup_coder.install(settings))
    assert not settings.state_path.exists()
    assert settings.env_path.read_text() == "API_KEY=private-key\n"
    assert not (tmp_path / "coder-setup.json").exists()
    assert fake.closed


@pytest.mark.parametrize("events, succeeds", [
    ([{"status": "pulling", "total": 10, "completed": 10}, {"status": "success"}], True),
    ([{"status": "pulling"}], False),
    ([{"error": "update Ollama"}], False),
])
def test_actual_http_pull_requires_success_and_propagates_failures(events, succeeds):
    requests = []
    def handle(request):
        requests.append(request)
        return httpx.Response(200, content="\n".join(json.dumps(item) for item in events))

    async def run():
        client = HttpxOllama("http://ollama.test")
        await client._http.aclose()
        client._http = httpx.AsyncClient(base_url="http://ollama.test", transport=httpx.MockTransport(handle))
        try:
            result = [chunk async for chunk in client.pull_model(setup_coder.RECOMMENDED_MODEL)]
            assert result[-1]["status"] == "success"
        finally:
            await client.aclose()

    if succeeds:
        asyncio.run(run())
    else:
        with pytest.raises(OllamaError):
            asyncio.run(run())
    assert requests[0].url.path == "/api/pull"
    assert json.loads(requests[0].content)["model"] == setup_coder.RECOMMENDED_MODEL


def test_recent_models_disable_thinking_without_changing_legacy_payloads():
    assert build_chat_body("ornith-1.5:9b", [], -1, 8192, 0.2)["think"] is False
    assert build_chat_body("qwen3.5:4b-q4_K_M", [], -1, 8192, 0.2)["think"] is False
    assert "think" not in build_chat_body("qwen2.5:7b", [], -1, 4096, 0.7)
