from pathlib import Path

from fastapi.testclient import TestClient

from app.main import create_app
from app.state import ModelStore
from tests.test_api import AUTH, FakeOllama, make_settings


def test_server_starts_answers_requests_and_closes_ollama(tmp_path: Path) -> None:
    ollama = FakeOllama()
    app = create_app(make_settings(tmp_path), ollama)
    with TestClient(app) as client:
        assert not ollama.closed
        assert client.get("/").json()["service"] == "home-desk"
        assert client.get("/health", headers=AUTH).json()["status"] == "ok"
        response = client.post("/chat", headers=AUTH, json={"messages": [{"role": "user", "content": "Hi"}]})
        assert response.status_code == 200
        assert '"done":true' in response.text
    assert ollama.closed


def test_warm_task_is_canceled_when_server_stops(tmp_path: Path) -> None:
    app = create_app(make_settings(tmp_path, warm_on_start=True), FakeOllama())
    with TestClient(app):
        assert app.state.warm_task is not None
    assert app.state.warm_task.done()


def test_state_explains_and_restores_selected_model(tmp_path: Path) -> None:
    path = tmp_path / "state.json"
    ModelStore(path, "qwen2.5:7b").set("ornith-1.5:9b")
    assert "_description" in path.read_text()
    assert ModelStore(path, "qwen2.5:7b").selected == "ornith-1.5:9b"
    for malformed in ("[]", "null", "{}", '{"model":"bad model"}'):
        path.write_text(malformed)
        assert ModelStore(path, "qwen2.5:7b").selected == "qwen2.5:7b"
