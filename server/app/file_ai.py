"""Ask the local model to propose a new version of one file.

Nothing in this module writes the file. The phone shows the proposal and
saves only after the user taps Save.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.config import Settings, clean_model
from app.files import CODE_EXTENSIONS, TextDocument
from app.ollama import OllamaError, OllamaUnreachable

_FENCE = re.compile(r"```([^\n`]*)\r?\n(.*?)```", re.DOTALL)
_FILE_CTX_CAP = 8192

_SYSTEM = (
    "You edit one file on the user's PC. "
    "Nothing is saved until they review your proposal and tap Save. "
    "Reply with a short explanation, then the complete new file inside one "
    "fenced block that starts with ```file. "
    "Keep every unchanged line. Do not replace sections with placeholders or comments "
    "that say the rest is unchanged."
)


def model_budget(content: str, num_ctx: int) -> tuple[int, int]:
    """Return the context length to request, and how many characters fit in it."""
    needed = (len(content) // 3) + 1600
    chosen = min(_FILE_CTX_CAP, max(num_ctx, needed))
    limit = max(256, chosen - 1200) * 3
    return chosen, limit


def too_large_for_model(limit: int) -> str:
    return (
        "This file is too large to send to the model. "
        f"About {limit} characters fit in the current context. "
        "You can still edit it by hand and save, or raise NUM_CTX in config/.env and restart Desk."
    )


def build_file_messages(path: Path, content: str, instruction: str) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": _SYSTEM},
        {
            "role": "user",
            "content": (
                f"Path: {path}\n\n"
                "Current file:\n"
                f"```\n{content}\n```\n\n"
                f"Instruction: {instruction.strip()}"
            ),
        },
    ]


def attachment_message(document: TextDocument) -> dict[str, str]:
    return {
        "role": "system",
        "content": (
            "The user attached a file from their PC. Use it as context. "
            "Do not claim the file was changed or saved.\n\n"
            f"Path: {document.path}\n"
            f"```\n{document.content}\n```"
        ),
    }


def extract_proposed(reply: str) -> str | None:
    matches = list(_FENCE.finditer(reply))
    if not matches:
        return None
    tagged = [match for match in matches if match.group(1).strip().lower() in {"file", "proposed"}]
    pool = tagged or matches
    chosen = max(pool, key=lambda match: len(match.group(2)))
    body = chosen.group(2)
    if body == "":
        return None
    return body


def is_code_file(path: Path) -> bool:
    return path.suffix.lower() in CODE_EXTENSIONS


async def select_file_model(ollama: object, path: Path, requested: str | None, settings: Settings) -> str:
    if requested and requested.strip():
        return clean_model(requested)
    if not is_code_file(path):
        return settings.default_model
    try:
        tags = await ollama.tags()  # type: ignore[attr-defined]
    except (OllamaUnreachable, OllamaError):
        return settings.default_model
    names = set()
    for item in tags:
        if isinstance(item, dict) and isinstance(item.get("name"), str):
            names.add(item["name"])
    if settings.coder_model in names:
        return settings.coder_model
    return settings.default_model
