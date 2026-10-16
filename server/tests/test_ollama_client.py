"""The real HTTP client against a tiny stand-in for Ollama."""

from __future__ import annotations

import json
import threading
from pathlib import Path

from fastapi.testclient import TestClient
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from app.main import create_app
from app.ollama import HttpxOllama, OllamaUnreachable
from tests.test_api import AUTH, make_settings


class OllamaHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/api/version":
            self._send({"version": "test"})
        elif self.path == "/api/tags":
            self._send(
                {
                    "models": [
                        {
                            "name": "qwen2.5:7b",
                            "size": 4_700_000_000,
                            "details": {
                                "parameter_size": "7.6B",
                                "quantization_level": "Q4_K_M",
                            },
                        }
                    ]
                }
            )
        elif self.path == "/api/ps":
            self._send({"models": [{"name": "qwen2.5:7b", "model": "qwen2.5:7b"}]})
        else:
            self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        self.server.bodies.append(body)  # type: ignore[attr-defined]
        if body.get("stream") is False:
            self._send({"model": body["model"], "done": True, "done_reason": "load"})
            return
        lines = [
            json.dumps({"message": {"role": "assistant", "content": "Hi"}, "done": False}),
            json.dumps({"message": {"role": "assistant", "content": ""}, "done": True}),
        ]
        payload = ("\n".join(lines) + "\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _send(self, data: dict) -> None:
        payload = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: object) -> None:
        return


def test_client_streams_and_warms(tmp_path: Path) -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 0), OllamaHandler)
    server.bodies = []  # type: ignore[attr-defined]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    ollama = HttpxOllama(base, connect_timeout=1.0, read_timeout=2.0)
    settings = make_settings(tmp_path, ollama_base_url=base, warm_on_start=True)
    app = create_app(settings, ollama)
    try:
        with TestClient(app) as client:
            health = client.get("/health", headers=AUTH)
            chat = client.post(
                "/chat",
                headers=AUTH,
                json={"messages": [{"role": "user", "content": "Hello"}]},
            )
        assert health.status_code == 200
        assert health.json()["ollama"] == "up"
        assert health.json()["model_loaded"] is True
        assert chat.status_code == 200
        assert 'data: {"content":"Hi"}' in chat.text
        assert '"done":true' in chat.text
        warm = next(body for body in server.bodies if body.get("messages") == [])  # type: ignore[attr-defined]
        assert warm["keep_alive"] == -1
        assert warm["model"] == "qwen2.5:7b"
        sent = next(body for body in server.bodies if body.get("stream") is True)  # type: ignore[attr-defined]
        assert sent["keep_alive"] == -1
        assert sent["options"]["num_ctx"] == 4096
    finally:
        server.shutdown()
        thread.join(timeout=3)
        server.server_close()


def test_client_reports_a_closed_port() -> None:
    import asyncio

    ollama = HttpxOllama("http://127.0.0.1:9", connect_timeout=0.2, read_timeout=0.2)

    async def check() -> None:
        try:
            await ollama.version()
            raise AssertionError("expected unreachable")
        except OllamaUnreachable as exc:
            assert "not answering" in str(exc)
        finally:
            await ollama.aclose()

    asyncio.run(check())
