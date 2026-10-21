"""Load config/.env. Environment variables win over the file."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

# Ollama's qwen2.5:7b tag is the instruct model at Q4_K_M (~4.7 GB).
# On a 12 GB NVIDIA card that leaves several gigabytes for the KV cache,
# and a 7B Q4 generates tokens quickly. Speed is the point of this default.
DEFAULT_MODEL = "qwen2.5:7b"
DEFAULT_CODER_MODEL = "qwen2.5-coder:14b"
DEFAULT_PORT = 8787
DEFAULT_NUM_CTX = 4096
DEFAULT_MAX_UPLOAD_MB = 200
DEFAULT_MAX_TEXT_BYTES = 1_000_000

_MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")


def project_root() -> Path:
    here = Path(__file__).resolve()
    for candidate in [here.parent, *here.parents]:
        if (candidate / "config" / ".env.example").is_file() and (candidate / "server").is_dir():
            return candidate
    raise SystemExit(
        "Could not find the project folder (config/.env.example). "
        "Start the server with start.sh or start.bat from the project folder."
    )


def parse_keep_alive(raw: str | None) -> int | str:
    text = (raw or "-1").strip()
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    if not text:
        return -1
    return text


def parse_num_ctx(raw: str | None) -> int:
    text = (raw or str(DEFAULT_NUM_CTX)).strip()
    try:
        value = int(text)
    except ValueError as exc:
        raise SystemExit("NUM_CTX must be a whole number, for example 4096.") from exc
    if not 512 <= value <= 32768:
        raise SystemExit("NUM_CTX must be between 512 and 32768.")
    return value


def parse_port(raw: str | None) -> int:
    text = (raw or str(DEFAULT_PORT)).strip()
    try:
        value = int(text)
    except ValueError as exc:
        raise SystemExit("PORT must be a whole number, for example 8787.") from exc
    if not 1 <= value <= 65535:
        raise SystemExit("PORT must be between 1 and 65535.")
    return value


def as_bool(raw: str | None, default: bool) -> bool:
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def clean_model(name: str) -> str:
    text = name.strip()
    if not _MODEL_RE.fullmatch(text):
        raise ValueError("Model names look like qwen2.5:7b.")
    return text


def default_workspace() -> str:
    return str(Path.home() / "Desk")


def parse_allowed_roots(raw: str | None) -> tuple[str, ...]:
    """ALL means every drive. Otherwise a semicolon-separated folder list."""
    text = (raw or "").strip()
    if not text:
        return (default_workspace(),)
    if text.upper() == "ALL":
        return ("ALL",)
    parts: list[str] = []
    for piece in text.split(";"):
        item = piece.strip().strip('"').strip("'")
        if item:
            parts.append(item)
    return tuple(parts) if parts else (default_workspace(),)


def parse_max_upload_bytes(raw: str | None) -> int:
    text = (raw or str(DEFAULT_MAX_UPLOAD_MB)).strip()
    try:
        value = int(text)
    except ValueError as exc:
        raise SystemExit("MAX_UPLOAD_MB must be a whole number, for example 200.") from exc
    if not 1 <= value <= 2048:
        raise SystemExit("MAX_UPLOAD_MB must be between 1 and 2048.")
    return value * 1024 * 1024


def parse_max_text_bytes(raw: str | None) -> int:
    text = (raw or str(DEFAULT_MAX_TEXT_BYTES)).strip()
    try:
        value = int(text)
    except ValueError as exc:
        raise SystemExit("MAX_TEXT_BYTES must be a whole number, for example 1000000.") from exc
    if not 1024 <= value <= 20_000_000:
        raise SystemExit("MAX_TEXT_BYTES must be between 1024 and 20000000.")
    return value


@dataclass(frozen=True)
class Settings:
    api_key: str
    ollama_base_url: str
    default_model: str
    keep_alive: int | str
    host: str
    port: int
    num_ctx: int
    temperature: float
    state_path: Path
    env_path: Path
    warm_on_start: bool
    connect_timeout: float = 2.0
    read_timeout: float = 8.0
    allowed_roots: tuple[str, ...] = ()
    max_upload_bytes: int = DEFAULT_MAX_UPLOAD_MB * 1024 * 1024
    max_text_bytes: int = DEFAULT_MAX_TEXT_BYTES
    coder_model: str = DEFAULT_CODER_MODEL

    @classmethod
    def load(cls) -> Settings:
        root = project_root()
        env_override = os.environ.get("ENV_FILE")
        env_path = Path(env_override) if env_override else root / "config" / ".env"
        if env_path.is_file():
            load_dotenv(env_path, override=False)

        api_key = os.environ.get("API_KEY", "").strip().strip('"').strip("'")
        if not api_key:
            raise SystemExit(
                "API_KEY is empty. Run start.sh or start.bat, or put a key in config/.env."
            )

        model_raw = os.environ.get("DEFAULT_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
        try:
            model = clean_model(model_raw)
        except ValueError as exc:
            raise SystemExit(f"DEFAULT_MODEL is not valid. {exc}") from exc

        try:
            temperature = float(os.environ.get("TEMPERATURE", "0.7"))
        except ValueError as exc:
            raise SystemExit("TEMPERATURE must be a number, for example 0.7.") from exc

        state_raw = os.environ.get("STATE_PATH")
        state_path = Path(state_raw) if state_raw else root / "config" / "state.json"
        ollama = os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434").strip().rstrip("/")

        coder_raw = os.environ.get("CODER_MODEL", DEFAULT_CODER_MODEL).strip() or DEFAULT_CODER_MODEL
        try:
            coder_model = clean_model(coder_raw)
        except ValueError as exc:
            raise SystemExit(f"CODER_MODEL is not valid. {exc}") from exc

        return cls(
            api_key=api_key,
            ollama_base_url=ollama or "http://127.0.0.1:11434",
            default_model=model,
            keep_alive=parse_keep_alive(os.environ.get("KEEP_ALIVE")),
            host=(os.environ.get("HOST") or "0.0.0.0").strip() or "0.0.0.0",
            port=parse_port(os.environ.get("PORT")),
            num_ctx=parse_num_ctx(os.environ.get("NUM_CTX")),
            temperature=temperature,
            state_path=state_path,
            env_path=env_path,
            warm_on_start=as_bool(os.environ.get("WARM_ON_START"), True),
            allowed_roots=parse_allowed_roots(os.environ.get("ALLOWED_ROOTS")),
            max_upload_bytes=parse_max_upload_bytes(os.environ.get("MAX_UPLOAD_MB")),
            max_text_bytes=parse_max_text_bytes(os.environ.get("MAX_TEXT_BYTES")),
            coder_model=coder_model,
        )

