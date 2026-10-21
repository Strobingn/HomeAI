import asyncio
from pathlib import Path

from fastapi.testclient import TestClient
import pytest

from app.main import create_app
from app.model_catalog import CATALOG, ModelDownloads, UNCENSORED_PACK
from app.ollama import OllamaError, build_chat_body
from tests.test_api import AUTH, FakeOllama, make_settings


class PullOllama(FakeOllama):
    def __init__(self, *, blocked=False, fail=False, missing=False):
        super().__init__()
        self.installed = []
        self.blocked = blocked
        self.fail = fail
        self.missing = missing
        self.pulled = []
        self.started = asyncio.Event()

    async def tags(self):
        return [{"name": name} for name in self.installed]

    async def pull_model(self, model):
        self.pulled.append(model)
        self.started.set()
        yield {"status": "pulling layer", "total": 100, "completed": 40}
        if self.blocked:
            await asyncio.Event().wait()
        if self.fail:
            raise OllamaError("disk is full")
        if not self.missing:
            self.installed.append(model)
        yield {"status": "success"}


def test_pack_installs_missing_models_without_selecting_or_loading():
    async def run():
        ollama = PullOllama()
        ollama.installed = [UNCENSORED_PACK[0].upper()]
        downloads = ModelDownloads(ollama)
        downloads.start(UNCENSORED_PACK + [UNCENSORED_PACK[-1]])
        await downloads.task
        assert downloads.state["phase"] == "complete"
        assert downloads.state["finished"] == UNCENSORED_PACK
        assert ollama.pulled == [UNCENSORED_PACK[-1]]
        assert not ollama.warm_bodies
    asyncio.run(run())


def test_one_job_only_and_cancelled_download_can_be_retried():
    async def run():
        ollama = PullOllama(blocked=True)
        downloads = ModelDownloads(ollama)
        downloads.start(UNCENSORED_PACK)
        await ollama.started.wait()
        with pytest.raises(RuntimeError):
            downloads.start(UNCENSORED_PACK)
        await downloads.close()
        assert downloads.state["phase"] == "cancelled"
        assert downloads.state["finished"] == []
        ollama.blocked = False
        downloads.start(UNCENSORED_PACK)
        await downloads.task
        assert downloads.state["phase"] == "complete"
    asyncio.run(run())


@pytest.mark.parametrize("kwargs, message", [({"fail": True}, "disk is full"), ({"missing": True}, "not found after downloading")])
def test_failed_or_unverified_download_is_not_reported_installed(kwargs, message):
    async def run():
        downloads = ModelDownloads(PullOllama(**kwargs))
        downloads.start(UNCENSORED_PACK)
        await downloads.task
        assert downloads.state["phase"] == "error"
        assert downloads.state["finished"] == []
        assert message in downloads.state["status"]
    asyncio.run(run())


def test_model_routes_require_auth_reject_arbitrary_tags_and_stale_cancel(tmp_path: Path):
    app = create_app(make_settings(tmp_path), PullOllama())
    with TestClient(app) as client:
        for path in ("/models/catalog", "/model-installs"):
            assert client.get(path).status_code == 401
        assert client.post("/model-installs", json={"models": UNCENSORED_PACK}).status_code == 401
        assert client.post("/model-installs/cancel", json={"id": "old"}).status_code == 401
        catalog = client.get("/models/catalog", headers=AUTH).json()
        assert len(catalog["models"]) == 5
        assert sum(row["uncensored"] for row in catalog["models"]) == 3
        assert client.post("/model-installs", headers=AUTH, json={"models": ["anything:999b"]}).status_code == 400
        assert client.post("/model-installs/cancel", headers=AUTH, json={"id": "old"}).status_code == 409
        selected = app.state.models.selected
        assert client.post("/model-installs", headers=AUTH, json={"models": UNCENSORED_PACK}).status_code == 202
        assert app.state.models.selected == selected
    assert app.state.ollama.closed


def test_abliterated_qwen_uses_fast_non_thinking_payload():
    for row in CATALOG:
        if "qwen3.5-abliterated" in row["model"]:
            assert build_chat_body(row["model"], [], -1, 8192, 0.2)["think"] is False
