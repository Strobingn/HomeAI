"""HTTP client for the Ollama server that is already installed on this PC."""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.messages import missing_model_message, unreachable_message


class OllamaUnreachable(Exception):
    pass


class OllamaError(Exception):
    pass


class ModelMissing(OllamaError):
    def __init__(self, model: str, message: str) -> None:
        super().__init__(message)
        self.model = model


def build_chat_body(
    model: str,
    messages: list[dict[str, str]],
    keep_alive: int | str,
    num_ctx: int,
    temperature: float,
) -> dict[str, Any]:
    return {
        "model": model,
        "messages": messages,
        "stream": True,
        "keep_alive": keep_alive,
        "options": {"num_ctx": num_ctx, "temperature": temperature},
    }


def build_warm_body(model: str, keep_alive: int | str) -> dict[str, Any]:
    # An empty messages list loads weights into VRAM and generates nothing.
    return {
        "model": model,
        "messages": [],
        "stream": False,
        "keep_alive": keep_alive,
    }


def _error_text(raw: str) -> str:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return raw.strip()
    if isinstance(data, dict) and isinstance(data.get("error"), str):
        return data["error"]
    return raw.strip()


class HttpxOllama:
    def __init__(
        self,
        base_url: str,
        connect_timeout: float = 2.0,
        read_timeout: float = 8.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self._short = httpx.Timeout(
            connect=connect_timeout,
            read=read_timeout,
            write=read_timeout,
            pool=connect_timeout,
        )
        self._warm = httpx.Timeout(connect=connect_timeout, read=180.0, write=30.0, pool=connect_timeout)
        self._stream = httpx.Timeout(connect=connect_timeout, read=None, write=30.0, pool=connect_timeout)
        # trust_env=False so HTTP_PROXY does not steal localhost traffic.
        self._http = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=self._short,
            trust_env=False,
            limits=httpx.Limits(max_keepalive_connections=8, max_connections=16, keepalive_expiry=300),
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        try:
            response = await self._http.request(method, path, **kwargs)
        except httpx.HTTPError as exc:
            raise OllamaUnreachable(unreachable_message(self.base_url)) from exc
        return response

    async def version(self) -> str:
        response = await self._request("GET", "/api/version")
        if response.status_code >= 400:
            raise OllamaError(_error_text(response.text) or f"Ollama returned {response.status_code}")
        data = response.json()
        version = data.get("version") if isinstance(data, dict) else None
        return version if isinstance(version, str) else "unknown"

    async def tags(self) -> list[dict[str, Any]]:
        response = await self._request("GET", "/api/tags")
        if response.status_code >= 400:
            raise OllamaError(_error_text(response.text) or f"Ollama returned {response.status_code}")
        data = response.json()
        models = data.get("models", []) if isinstance(data, dict) else []
        return [item for item in models if isinstance(item, dict)]

    async def running_names(self) -> list[str]:
        response = await self._request("GET", "/api/ps")
        if response.status_code >= 400:
            raise OllamaError(_error_text(response.text) or f"Ollama returned {response.status_code}")
        data = response.json()
        models = data.get("models", []) if isinstance(data, dict) else []
        names: list[str] = []
        for item in models:
            if not isinstance(item, dict):
                continue
            for key in ("name", "model"):
                value = item.get(key)
                if isinstance(value, str) and value not in names:
                    names.append(value)
        return names

    async def warm(self, body: dict[str, Any]) -> None:
        try:
            response = await self._http.post("/api/chat", json=body, timeout=self._warm)
        except httpx.HTTPError as exc:
            raise OllamaUnreachable(unreachable_message(self.base_url)) from exc
        await self._raise_for_model(response, str(body.get("model", "")))

    async def stream_chat(self, body: dict[str, Any]) -> AsyncIterator[dict[str, Any]]:
        model = str(body.get("model", ""))
        try:
            async with self._http.stream("POST", "/api/chat", json=body, timeout=self._stream) as response:
                if response.status_code >= 400:
                    raw = (await response.aread()).decode("utf-8", errors="replace")
                    self._raise_model_from_text(response.status_code, raw, model)
                async for line in response.aiter_lines():
                    if not line:
                        continue
                    try:
                        chunk = json.loads(line)
                    except json.JSONDecodeError as exc:
                        raise OllamaError("Ollama sent a reply that was not JSON.") from exc
                    if not isinstance(chunk, dict):
                        continue
                    error = chunk.get("error")
                    if isinstance(error, str) and error:
                        self._raise_model_from_text(500, json.dumps({"error": error}), model)
                    yield chunk
        except (OllamaError, OllamaUnreachable):
            raise
        except httpx.HTTPError as exc:
            raise OllamaUnreachable(unreachable_message(self.base_url)) from exc

    async def _raise_for_model(self, response: httpx.Response, model: str) -> None:
        if response.status_code < 400:
            try:
                data = response.json()
            except json.JSONDecodeError:
                return
            if isinstance(data, dict) and isinstance(data.get("error"), str):
                self._raise_model_from_text(response.status_code, response.text, model)
            return
        self._raise_model_from_text(response.status_code, response.text, model)

    def _raise_model_from_text(self, status: int, raw: str, model: str) -> None:
        message = _error_text(raw) or f"Ollama returned {status}"
        lowered = message.lower()
        if status == 404 or "not found" in lowered or "try pulling" in lowered:
            raise ModelMissing(model, missing_model_message(model))
        raise OllamaError(message)
