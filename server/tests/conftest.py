"""Isolate tests from a developer config/.env and from a real Ollama port."""

from __future__ import annotations

import os
import tempfile
from pathlib import Path

_tmpdir = Path(tempfile.mkdtemp(prefix="homedesk-pytest-"))
os.environ["API_KEY"] = "test-key"
os.environ["WARM_ON_START"] = "0"
os.environ["STATE_PATH"] = str(_tmpdir / "state.json")
os.environ["ENV_FILE"] = str(_tmpdir / "missing.env")
os.environ["OLLAMA_BASE_URL"] = "http://127.0.0.1:9"
os.environ["DEFAULT_MODEL"] = "qwen2.5:7b"
os.environ["KEEP_ALIVE"] = "-1"
os.environ["NUM_CTX"] = "4096"
os.environ["HOST"] = "127.0.0.1"
os.environ["PORT"] = "8787"
