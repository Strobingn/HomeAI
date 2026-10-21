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


_FILE_DEFAULTS = (
    ("ALLOWED_ROOTS", ""),
    ("ALLOW_ALL_DRIVES", "0"),
    ("MAX_UPLOAD_MB", "200"),
    ("MAX_TEXT_BYTES", "1000000"),
    ("CODER_MODEL", "qwen2.5-coder:14b"),
)


def _assigned_keys(text: str) -> set[str]:
    names: set[str] = set()
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        names.add(stripped.split("=", 1)[0].strip())
    return names


def append_file_settings(text: str) -> str:
    """Add file settings that an older config/.env does not have yet."""
    present = _assigned_keys(text)
    missing = [(key, value) for key, value in _FILE_DEFAULTS if key not in present]
    if not missing:
        return text if text.endswith("\n") else text + "\n"
    lines = [text.rstrip(), ""]
    if "ALLOWED_ROOTS" not in present:
        lines.extend(
            [
                "# Files. Blank limits access to the Desk folder inside your home folder.",
                "# To add project folders, use semicolon-separated absolute paths:",
                r"# ALLOWED_ROOTS=C:\Users\Austin\Desktop;D:\Work",
            ]
        )
    for key, value in missing:
        lines.append(f"{key}={value}")
    return "\n".join(lines).rstrip() + "\n"


def ensure_env(root: Path) -> str | None:
    """Write config/.env if needed. Returns the new key, or None if one was already set."""
    config_dir = root / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    env_path = config_dir / ".env"
    example = config_dir / ".env.example"
    if env_path.is_file():
        original = env_path.read_text(encoding="utf-8")
    elif example.is_file():
        original = example.read_text(encoding="utf-8")
    else:
        original = "API_KEY=\nDEFAULT_MODEL=qwen2.5:7b\n"
    key = current_api_key(original)
    created: str | None = None
    text = original
    if not key:
        created = secrets.token_urlsafe(24)
        text = upsert_key(text, created)
    text = append_file_settings(text)
    if created is not None or not env_path.is_file() or text != original:
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

