from __future__ import annotations

import asyncio
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.messages import missing_model_message, unreachable_message
from app.ollama import ModelMissing, OllamaUnreachable

AUTH = {"Authorization": "Bearer test-key"}


def make_settings(tmp_path: Path, **overrides: object) -> Settings:
    values: dict[str, object] = {
        "api_key": "test-key",
        "ollama_base_url": "http://127.0.0.1:11434",
        "default_model": "qwen2.5:7b",
        "keep_alive": -1,
        "host": "127.0.0.1",
        "port": 8787,
        "num_ctx": 4096,
        "temperature": 0.7,
        "state_path": tmp_path / "state.json",
        "env_path": tmp_path / ".env",
        "warm_on_start": False,
        "connect_timeout": 0.2,
        "read_timeout": 0.2,
    }
    values.update(overrides)
    return Settings(**values)  # type: ignore[arg-type]


class FakeOllama:
    def __init__(self) -> None:
        self.up = True
        self.version_str = "0.9.0"
        self.installed = ["qwen2.5:7b"]
        self.loaded: list[str] = []
        self.warm_bodies: list[dict] = []
        self.chat_bodies: list[dict] = []
        self.tokens = ["Hello", " there"]
        self.closed = False

    async def aclose(self) -> None:
        self.closed = True

    async def version(self) -> str:
        if not self.up:
            raise OllamaUnreachable(unreachable_message("http://127.0.0.1:11434"))
        return self.version_str

    async def tags(self) -> list[dict]:
        if not self.up:
            raise OllamaUnreachable(unreachable_message("http://127.0.0.1:11434"))
        return [
            {
                "name": name,
                "size": 4_700_000_000,
                "details": {"parameter_size": "7.6B", "quantization_level": "Q4_K_M"},
            }
            for name in self.installed
        ]

    async def running_names(self) -> list[str]:
        if not self.up:
            raise OllamaUnreachable(unreachable_message("http://127.0.0.1:11434"))
        return list(self.loaded)

    async def warm(self, body: dict) -> None:
        self.warm_bodies.append(body)
        if not self.up:
            raise OllamaUnreachable(unreachable_message("http://127.0.0.1:11434"))
        model = body["model"]
        if model not in self.installed:
            raise ModelMissing(model, missing_model_message(model))
        if model not in self.loaded:
            self.loaded.append(model)

    async def stream_chat(self, body: dict):
        self.chat_bodies.append(body)
        if not self.up:
            raise OllamaUnreachable(unreachable_message("http://127.0.0.1:11434"))
        model = body["model"]
        if model not in self.installed:
            raise ModelMissing(model, missing_model_message(model))
        for token in self.tokens:
            yield {"message": {"role": "assistant", "content": token}, "done": False}
        yield {"message": {"role": "assistant", "content": ""}, "done": True}


def _app(tmp_path: Path, fake: FakeOllama | None = None, file_service=None, **overrides: object):
    fake = fake or FakeOllama()
    return create_app(make_settings(tmp_path, **overrides), fake, file_service=file_service), fake


def test_root_is_public(tmp_path: Path) -> None:
    app, _ = _app(tmp_path)
    with TestClient(app) as client:
        response = client.get("/")
    assert response.status_code == 200
    assert response.json()["service"] == "home-desk"
    assert response.json()["server_version"] == "2.3.1"
    assert set(response.json()["capabilities"]) >= {"workspace_chat", "workspace_apply"}


def test_health_requires_the_key(tmp_path: Path) -> None:
    app, _ = _app(tmp_path)
    with TestClient(app) as client:
        missing = client.get("/health")
        wrong = client.get("/health", headers={"Authorization": "Bearer nope"})
        header = client.get("/health", headers={"X-API-Key": "test-key"})
    assert missing.status_code == 401
    assert "API key" in missing.json()["detail"]
    assert wrong.status_code == 401
    assert header.status_code == 200


def test_health_reports_a_warm_model(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.loaded = ["qwen2.5:7b"]
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        body = client.get("/health", headers=AUTH).json()
    assert body["status"] == "ok"
    assert body["ollama"] == "up"
    assert body["model"] == "qwen2.5:7b"
    assert body["model_loaded"] is True
    assert body["ollama_version"] == "0.9.0"
    assert body["server_version"] == "2.3.1"
    assert set(body["capabilities"]) >= {"workspace_chat", "workspace_apply"}


def test_health_when_ollama_is_down(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.up = False
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        body = client.get("/health", headers=AUTH).json()
    assert body["status"] == "degraded"
    assert body["ollama"] == "down"
    assert "already installed" in body["detail"]
    assert "workspace_chat" in body["capabilities"]


def test_health_when_the_model_is_not_pulled(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.installed = []
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        body = client.get("/health", headers=AUTH).json()
    assert body["ollama"] == "up"
    assert "ollama pull qwen2.5:7b" in body["detail"]


def test_list_models(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.loaded = ["qwen2.5:7b"]
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        body = client.get("/models", headers=AUTH).json()
    assert body["selected"] == "qwen2.5:7b"
    assert body["models"][0]["quantization"] == "Q4_K_M"
    assert body["models"][0]["parameter_size"] == "7.6B"
    assert body["models"][0]["loaded"] is True


def test_list_models_when_ollama_is_down(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.up = False
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        response = client.get("/models", headers=AUTH)
    assert response.status_code == 503
    assert "Ollama" in response.json()["detail"]


def test_select_model_persists_and_warms(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.installed.append("llama3.1:8b")
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        selected = client.put("/model", headers=AUTH, json={"model": "llama3.1:8b"})
        assert selected.status_code == 200
        assert selected.json()["model"] == "llama3.1:8b"
        assert selected.json()["warming"] is True
        again = client.get("/model", headers=AUTH)
    assert again.json()["model"] == "llama3.1:8b"
    assert fake.warm_bodies[-1]["model"] == "llama3.1:8b"
    assert fake.warm_bodies[-1]["keep_alive"] == -1
    assert fake.warm_bodies[-1]["messages"] == []

    fake2 = FakeOllama()
    fake2.installed.append("llama3.1:8b")
    app2, _ = _app(tmp_path, fake2)
    with TestClient(app2) as client:
        saved = client.get("/model", headers=AUTH)
    assert saved.json()["model"] == "llama3.1:8b"


def test_select_model_rejects_a_blank_name(tmp_path: Path) -> None:
    app, _ = _app(tmp_path)
    with TestClient(app) as client:
        response = client.put("/model", headers=AUTH, json={"model": "bad name"})
    assert response.status_code == 400


def test_chat_streams_tokens_and_keeps_the_model_warm(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.tokens = ["Hello", " café"]
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        response = client.post(
            "/chat",
            headers=AUTH,
            json={"messages": [{"role": "user", "content": "Hi"}]},
        )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert 'data: {"content":"Hello"}' in response.text
    assert "café" in response.text
    assert '"done":true' in response.text
    sent = fake.chat_bodies[-1]
    assert sent["model"] == "qwen2.5:7b"
    assert sent["keep_alive"] == -1
    assert sent["stream"] is True
    assert sent["options"]["num_ctx"] == 4096
    assert sent["messages"] == [{"role": "user", "content": "Hi"}]


def test_chat_model_override(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.installed.append("llama3.1:8b")
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        client.post(
            "/chat",
            headers=AUTH,
            json={"model": "llama3.1:8b", "messages": [{"role": "user", "content": "Hi"}]},
        )
    assert fake.chat_bodies[-1]["model"] == "llama3.1:8b"


def test_chat_reports_a_missing_model(tmp_path: Path) -> None:
    app, fake = _app(tmp_path)
    fake.installed = []
    with TestClient(app) as client:
        response = client.post(
            "/chat",
            headers=AUTH,
            json={"messages": [{"role": "user", "content": "Hi"}]},
        )
    assert response.status_code == 200
    assert "ollama pull qwen2.5:7b" in response.text


def test_chat_reports_ollama_down(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.up = False
    app, _ = _app(tmp_path, fake)
    with TestClient(app) as client:
        response = client.post(
            "/chat",
            headers=AUTH,
            json={"messages": [{"role": "user", "content": "Hi"}]},
        )
    assert "not answering" in response.text


def test_chat_rejects_an_empty_message_list(tmp_path: Path) -> None:
    app, _ = _app(tmp_path)
    with TestClient(app) as client:
        response = client.post("/chat", headers=AUTH, json={"messages": []})
    assert response.status_code == 422
    assert "message" in response.json()["detail"]


def test_lifespan_warms_the_default_model(tmp_path: Path) -> None:
    fake = FakeOllama()
    app, _ = _app(tmp_path, fake, warm_on_start=True)

    async def run() -> None:
        async with app.router.lifespan_context(app):
            await app.state.warm_task

    asyncio.run(run())
    assert fake.warm_bodies[0]["model"] == "qwen2.5:7b"
    assert fake.warm_bodies[0]["keep_alive"] == -1
    assert fake.closed is True


def test_lifespan_stays_up_when_the_model_is_missing(tmp_path: Path) -> None:
    fake = FakeOllama()
    fake.installed = []
    app, _ = _app(tmp_path, fake, warm_on_start=True)

    async def run() -> None:
        async with app.router.lifespan_context(app):
            await app.state.warm_task

    asyncio.run(run())
    assert fake.warm_bodies
    assert fake.closed is True

