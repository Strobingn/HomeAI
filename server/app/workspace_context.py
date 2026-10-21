"""Search a project independently of the model's much smaller context window."""
from __future__ import annotations

import fnmatch
import json
import os
import re
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any

from app.files import FileAccessError, FileService, is_text_name, is_within

EXCLUDED_DIRS = {"node_modules", "build", "dist", "target", "vendor", "venv", "__pycache__", "coverage"}
SECRET_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore"}
MAX_SCAN = 100_000
MAX_FILES = 20_000
MAX_READ = 16_000_000
MAX_SOURCE_FILE = 1_000_000
MAX_CONTEXT_FILES = 16
_STOP = set("the and for with this that from into have has are was you your can could would should "
            "please file files folder project code app all fix improve change changes explain work "
            "what how why want need make about now it its is to of in on an as be me my".split())


def excluded(relative: str, patterns: list[str]) -> bool:
    parts = PurePosixPath(relative).parts
    if any(p.startswith(".") or p.casefold() in EXCLUDED_DIRS for p in parts):
        return True
    name = parts[-1].lower() if parts else ""
    if PurePosixPath(name).suffix in SECRET_SUFFIXES or name in {
        "credentials.json", "secrets.json", "signing.properties", "local.properties",
    }:
        return True
    return any(fnmatch.fnmatch(relative, p) or fnmatch.fnmatch(name, p) for p in patterns)


def workspace_target(service: FileService, root: Path, relative: str) -> Path:
    target = root / relative
    cursor = target
    while cursor != root:
        if cursor.is_symlink():
            raise FileAccessError("Workspace changes cannot follow file or folder shortcuts.", 403)
        cursor = cursor.parent
    real = target.resolve()
    if not is_within(real, root) or service._real_root(real, service.bounds()) is None:
        raise FileAccessError("The file is outside the selected workspace.", 403)
    return target


@dataclass
class Snapshot:
    root: Path
    # Originals are retained for conflict checks and exact edits, even for excerpts.
    selected: dict[str, str]
    report: dict[str, Any]
    patterns: list[str]
    excerpts: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    model_tree: str = ""

    @property
    def prompt(self) -> dict[str, Any]:
        return {
            "coverage": {key: self.report[key] for key in (
                "indexed", "searched", "omitted", "skipped", "scan_complete", "read_limited",
            ) if key in self.report},
            "file_index": self.model_tree,
            "contents": {path: self.excerpts.get(path, content) for path, content in self.selected.items()},
        }


def _terms(query: str) -> set[str]:
    split = re.sub(r"([a-z])([A-Z])", r"\1 \2", query)
    words = dict.fromkeys(term for term in re.findall(r"[a-z0-9_]{2,}", split.lower()) if term not in _STOP)
    return set(list(words)[:48])


def _path_score(relative: str, query: str, terms: set[str]) -> int:
    path = PurePosixPath(relative.lower())
    name = path.name
    # Explicit names outrank generic content matches, including named tests.
    named = any(re.search(r"(?<![\w.-])" + re.escape(value) + r"(?![\w.-])", query.lower())
                for value in (relative.lower(), name))
    score = 1000 if named else 0
    score += sum(24 for term in terms if term in relative.lower())
    if name in {"readme", "readme.md", "package.json", "pyproject.toml", "settings.gradle.kts", "build.gradle.kts"}:
        score += 80 if len(path.parts) == 1 else 45
    if name in {"main.py", "app.py", "__main__.py", "mainactivity.kt", "deskapp.kt", "app.kt", "app.tsx", "index.tsx", "androidmanifest.xml"}:
        score += 90
    if any(part in {"src", "main", "app", "ui", "screens"} for part in path.parts[:-1]):
        score += 25
    if any(part in {"test", "tests", "androidtest"} for part in path.parts[:-1]) or re.search(r"(?:test|spec)\.[^.]+$", name):
        score -= 65
    if name in {"gradlew", "gradlew.bat", "license", "license.md", "notice", "proguard-rules.pro", "__init__.py"} or name.endswith((".lock", "-lock.json")):
        score -= 100
    return score


def _excerpt(content: str, terms: set[str], limit: int) -> dict[str, Any] | None:
    """Pick a line window around matches; preserve its exact text for patching."""
    if limit < 40 or not content.strip():
        return None
    lines = content.splitlines(keepends=True)
    scores = [sum(min(3, line.lower().count(term)) for term in terms) for line in lines]
    # A source declaration is a better overview than a block of imports.
    declarations = [bool(re.match(r"\s*(?:(?:public|private|internal|export|async|suspend|data|open)\s+)*(?:class|fun|def|function|interface|object)\s", line)) for line in lines]
    anchor = max(range(len(lines)), key=lambda i: (scores[i] * 20 + int(declarations[i]), -i))
    start = max(0, anchor - 3)
    while start < anchor and sum(len(line) for line in lines[start:anchor + 1]) > limit:
        start += 1
    end = start
    text = ""
    partial_line = False
    while end < len(lines) and len(text) + len(lines[end]) <= limit:
        text += lines[end]
        end += 1
    if not text:
        # Minified files and single long lines still provide bounded context.
        line = lines[start]
        hits = [line.lower().find(term) for term in terms if term in line.lower()]
        offset = max(0, min(hits, default=0) - limit // 3)
        text = line[offset:offset + limit]
        end = start + 1
        partial_line = offset > 0 or len(text) < len(line)
    return {"start_line": start + 1, "end_line": end, "content": text, "partial_line": partial_line}


def _compact_tree(names: list[str], scores: dict[str, int], budget: int) -> str:
    groups: dict[str, list[str]] = defaultdict(list)
    for name in names:
        path = PurePosixPath(name)
        groups[str(path.parent)].append(path.name)
    rows: list[str] = []
    used = 0
    for directory in sorted(groups, key=lambda d: (-max(scores[f"{d}/{n}" if d != "." else n] for n in groups[d]), d)):
        prefix = directory + "/: "
        row = prefix
        for name in sorted(groups[directory]):
            addition = (", " if row != prefix else "") + name
            if used + len(row) + len(addition) + 1 > budget:
                break
            row += addition
        if row != prefix:
            rows.append(row)
            used += len(row) + 1
    return "\n".join(rows)


def collect_context(service: FileService, raw: str, query: str, budget: int, *, tree_budget: int = 2000) -> Snapshot:
    root = service.locate(raw, service.bounds(), must_exist=True, kind="dir").real
    patterns: list[str] = []
    ignore = root / ".deskignore"
    if ignore.is_file() and not ignore.is_symlink() and ignore.stat().st_size <= 16_384:
        patterns = [line.strip() for line in ignore.read_text(encoding="utf-8").splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
    names: list[str] = []
    scanned = skipped = read = 0
    scan_limited = read_limited = False
    terms = _terms(query)

    def scan_error(_: OSError) -> None:
        nonlocal skipped, scan_limited
        skipped += 1
        scan_limited = True

    for directory, dirs, files in os.walk(root, followlinks=False, onerror=scan_error):
        current = Path(directory)
        scanned += 1
        if scanned > MAX_SCAN:
            scan_limited = True
            break
        dirs[:] = sorted(d for d in dirs if not excluded((current / d).relative_to(root).as_posix(), patterns)
                         and not (current / d).is_symlink() and is_within((current / d).resolve(), root))
        if len(current.relative_to(root).parts) >= 48 and dirs:
            dirs[:] = []
            scan_limited = True
        for name in sorted(files):
            scanned += 1
            if scanned > MAX_SCAN or len(names) >= MAX_FILES:
                scan_limited = True
                break
            path = current / name
            relative = path.relative_to(root).as_posix()
            if excluded(relative, patterns) or path.is_symlink() or not is_text_name(name):
                skipped += 1
                continue
            names.append(relative)
        if scanned > MAX_SCAN or len(names) >= MAX_FILES:
            scan_limited = True
            break
    scores = {name: _path_score(name, query, terms) for name in names}
    candidates: list[tuple[int, str, str]] = []
    for relative in sorted(names, key=lambda n: (-scores[n], n)):
        try:
            path = workspace_target(service, root, relative)
            size = path.stat().st_size
            if size > min(MAX_SOURCE_FILE, service.max_text_bytes) or read + size > MAX_READ:
                read_limited = True
                continue
            doc = service.read_text(str(path))
            read += doc.size
            if not doc.content.strip():
                continue
            lowered = doc.content.lower()
            content_score = sum(min(4, lowered.count(term)) for term in terms)
            candidates.append((scores[relative] + content_score, relative, doc.content))
        except (FileAccessError, OSError, ValueError):
            skipped += 1
    selected: dict[str, str] = {}
    excerpts: dict[str, list[dict[str, Any]]] = {}
    used = 0
    ranked = sorted(candidates, key=lambda row: (-row[0], row[1]))
    # Spread context over useful source files instead of only files small enough
    # to fit whole. Explicitly named files get a larger slice of the budget.
    quota = max(400, budget // min(12, max(1, len(ranked))))
    named_count = sum(score >= 1000 for score in scores.values())
    for _, relative, content in ranked:
        if len(selected) >= MAX_CONTEXT_FILES:
            break
        remaining = budget - used
        allowance = min(remaining, max(quota, budget // max(3, min(12, named_count))) if scores[relative] >= 1000 else quota)
        full_cost = len(json.dumps({"path": relative, "content": content}, ensure_ascii=False))
        if full_cost <= allowance:
            cost = full_cost
            fragment = None
        else:
            fragment = _excerpt(content, terms, allowance - len(relative) - 180)
            if fragment is None:
                continue
            cost = len(json.dumps({"path": relative, "excerpts": [fragment]}, ensure_ascii=False))
            # JSON escaping can expand source text; trim until it really fits.
            while cost > allowance and fragment is not None:
                fragment = _excerpt(content, terms, max(0, len(fragment["content"]) // 2))
                cost = len(json.dumps({"path": relative, "excerpts": [fragment]}, ensure_ascii=False))
            if fragment is None:
                continue
        selected[relative] = content
        if fragment is not None:
            excerpts[relative] = [fragment]
        used += cost
    # The phone gets a larger searchable index preview; it never goes wholesale
    # into Ollama's prompt. A preview limit is not an incomplete folder scan.
    tree: list[str] = []
    tree_size = 0
    for name in names:
        if tree_size + len(name) > 256_000:
            break
        tree.append(name)
        tree_size += len(name)
    report = {"root": str(root), "files": list(selected), "tree": tree,
              "indexed": len(names), "searched": len(candidates), "omitted": len(names) - len(selected),
              "skipped": skipped, "truncated": scan_limited, "scan_complete": not scan_limited,
              "tree_limited": len(tree) < len(names), "read_limited": read_limited,
              "partial": list(excerpts), "context_chars": used}
    return Snapshot(root, selected, report, patterns, excerpts, _compact_tree(names, scores, tree_budget))
