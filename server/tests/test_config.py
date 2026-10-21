from pathlib import Path

import pytest

from app.config import (
    DEFAULT_MODEL,
    clean_model,
    parse_allowed_roots,
    parse_keep_alive,
    parse_max_text_bytes,
    parse_max_upload_bytes,
    parse_num_ctx,
)
from app.ensure_config import append_file_settings, current_api_key, ensure_env, upsert_key
from app.ollama import build_chat_body, build_warm_body


def test_default_model_is_the_fast_7b_quant() -> None:
    assert DEFAULT_MODEL == "qwen2.5:7b"


def test_keep_alive_accepts_forever_and_durations() -> None:
    assert parse_keep_alive("-1") == -1
    assert parse_keep_alive("24h") == "24h"
    assert parse_keep_alive(None) == -1


def test_num_ctx_rejects_extremes() -> None:
    assert parse_num_ctx("4096") == 4096
    with pytest.raises(SystemExit):
        parse_num_ctx("32")


def test_clean_model_rejects_spaces() -> None:
    assert clean_model(" qwen2.5:7b ") == "qwen2.5:7b"
    with pytest.raises(ValueError):
        clean_model("not a model")


def test_chat_body_keeps_the_model_warm() -> None:
    body = build_chat_body("qwen2.5:7b", [{"role": "user", "content": "Hi"}], -1, 4096, 0.7)
    assert body["keep_alive"] == -1
    assert body["stream"] is True
    assert body["options"]["num_ctx"] == 4096


def test_warm_body_loads_without_generating() -> None:
    body = build_warm_body("qwen2.5:7b", -1)
    assert body["messages"] == []
    assert body["stream"] is False
    assert body["keep_alive"] == -1


def test_ensure_env_fills_a_blank_key_once(tmp_path: Path) -> None:
    config = tmp_path / "config"
    config.mkdir()
    (config / ".env.example").write_text(
        "API_KEY=\nDEFAULT_MODEL=qwen2.5:7b\nOLLAMA_BASE_URL=http://127.0.0.1:11434\n",
        encoding="utf-8",
    )
    created = ensure_env(tmp_path)
    assert created
    text = (config / ".env").read_text(encoding="utf-8")
    assert current_api_key(text) == created
    assert "DEFAULT_MODEL=qwen2.5:7b" in text
    assert ensure_env(tmp_path) is None
    assert current_api_key((config / ".env").read_text(encoding="utf-8")) == created


def test_allowed_roots_default_to_home_workspace() -> None:
    assert parse_allowed_roots(None) == (str(Path.home() / "Desk"),)
    assert parse_allowed_roots("ALL") == ("ALL",)
    assert parse_max_upload_bytes("200") == 200 * 1024 * 1024
    assert parse_max_text_bytes(None) == 1_000_000
    with pytest.raises(SystemExit):
        parse_max_upload_bytes("0")


def test_append_file_settings_is_idempotent() -> None:
    once = append_file_settings("API_KEY=abc\n")
    assert "ALLOWED_ROOTS=\n" in once
    assert "CODER_MODEL=qwen2.5-coder:14b" in once
    twice = append_file_settings(once)
    assigned = [
        line.strip()
        for line in twice.splitlines()
        if line.strip().startswith("ALLOWED_ROOTS=")
    ]
    assert assigned == ["ALLOWED_ROOTS="]


def test_upsert_key_replaces_only_the_key_line() -> None:
    text = upsert_key("# comment\nAPI_KEY=\nPORT=8787\n", "abc")
    assert "API_KEY=abc" in text
    assert "PORT=8787" in text
    assert text.startswith("# comment")

