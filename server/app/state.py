"""Remember the selected model across restarts."""

from __future__ import annotations

import json
from pathlib import Path

from app.config import clean_model


class ModelStore:
    def __init__(self, path: Path, default: str) -> None:
        self.path = path
        self.selected = default
        self._load(default)

    def _load(self, default: str) -> None:
        if not self.path.is_file():
            self.selected = default
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            model = data.get("model", "") if isinstance(data, dict) else ""
            if not model:
                self.selected = default
                return
            self.selected = clean_model(model) if isinstance(model, str) else default
        except (OSError, json.JSONDecodeError, ValueError):
            self.selected = default

    def set(self, model: str) -> None:
        self.selected = model
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps({
            "_description": "model is the gateway's selected Ollama model tag. It is restored after restart and used when a chat request does not specify its own model. Desk updates it when a model is selected or installed.",
            "model": model,
        }, indent=2) + "\n"
        temporary = self.path.with_suffix(".json.tmp")
        temporary.write_text(payload, encoding="utf-8")
        temporary.replace(self.path)

