"""Create config/.env on the first start and fill in a blank API key."""

from __future__ import annotations

import os
import secrets
import sys
from pathlib import Path

from app.config import project_root


def current_api_key(text: str) -> str:
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        if name.strip() == "API_KEY":
            return value.strip().strip('"').strip("'")
    return ""


def upsert_key(text: str, key: str) -> str:
    lines = text.splitlines()
    found = False
    output: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("API_KEY=") and not stripped.startswith("#"):
            output.append(f"API_KEY={key}")
            found = True
        else:
            output.append(line)
    if not found:
        output.append(f"API_KEY={key}")
    return "\n".join(output).rstrip() + "\n"


def ensure_env(root: Path) -> str | None:
    """Write config/.env if needed. Returns the new key, or None if one was already set."""
    config_dir = root / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    env_path = config_dir / ".env"
    example = config_dir / ".env.example"
    if env_path.is_file():
        text = env_path.read_text(encoding="utf-8")
    elif example.is_file():
        text = example.read_text(encoding="utf-8")
    else:
        text = "API_KEY=\nDEFAULT_MODEL=qwen2.5:7b\n"
    key = current_api_key(text)
    created: str | None = None
    if not key:
        created = secrets.token_urlsafe(24)
        text = upsert_key(text, created)
    if created is not None or not env_path.is_file():
        env_path.write_text(text, encoding="utf-8")
        try:
            os.chmod(env_path, 0o600)
        except OSError:
            pass
    return created


def main() -> None:
    root = project_root()
    created = ensure_env(root)
    env_path = root / "config" / ".env"
    if created:
        print("")
        print("A new API key was saved. Type it into the phone once.")
        print("")
        print(f"API_KEY={created}")
        print("")
        print(f"It is also stored in {env_path}")
        print("")
    else:
        print(f"Using the API key already in {env_path}")


if __name__ == "__main__":
    try:
        main()
    except SystemExit as exc:
        if exc.code not in (0, None):
            print(exc, file=sys.stderr)
        raise
