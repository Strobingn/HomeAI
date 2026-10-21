"""Read and write files on this PC for the Desk phone app.

ALLOWED_ROOTS=ALL means every fixed, removable, and mapped drive. Those
drives are detected again on each request. A semicolon-separated list limits
Desk to those folders instead.

Paths are lexically normalized, then resolved. A path is refused when the
spelled location or the real location (after shortcuts) sits outside an
allowed drive or folder.
"""

from __future__ import annotations

import os
import re
import shutil
import string
import uuid
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

TRASH_NAME = ".trash"
DRIVE_REMOVABLE = 2
DRIVE_FIXED = 3
DRIVE_REMOTE = 4
_INCLUDED_DRIVE_TYPES = {DRIVE_REMOVABLE, DRIVE_FIXED, DRIVE_REMOTE}
_LIST_CAP = 5000

OUTSIDE = (
    "That path is outside the drives Desk can use. "
    "ALLOWED_ROOTS in config/.env is ALL for every drive, or a list of folders."
)

CODE_EXTENSIONS = {
    ".py", ".pyw", ".pyi", ".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx",
    ".kt", ".kts", ".java", ".c", ".h", ".cpp", ".cc", ".cxx", ".hpp", ".hh",
    ".cs", ".go", ".rs", ".rb", ".php", ".swift", ".scala", ".lua", ".pl",
    ".sh", ".bash", ".zsh", ".ps1", ".bat", ".cmd", ".sql", ".r", ".dart",
    ".vue", ".svelte", ".html", ".htm", ".css", ".scss", ".less",
    ".json", ".xml", ".yml", ".yaml", ".toml", ".gradle",
}

TEXT_EXTENSIONS = CODE_EXTENSIONS | {
    ".txt", ".md", ".markdown", ".rst", ".csv", ".tsv", ".log", ".ini", ".cfg",
    ".conf", ".env", ".properties", ".gitignore", ".dockerignore", ".editorconfig",
    ".svg", ".tex", ".adoc", ".srt", ".vtt", ".qml",
}

TEXT_NAMES = {
    "readme", "license", "licence", "dockerfile", "makefile", "gemfile",
    "rakefile", "procfile", "authors", "changelog", "copying", "notice",
}

BINARY_EXTENSIONS = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".heic", ".tif", ".tiff",
    ".pdf", ".zip", ".7z", ".rar", ".gz", ".tgz", ".bz2", ".xz", ".tar",
    ".exe", ".dll", ".so", ".dylib", ".bin", ".iso", ".img", ".dmg",
    ".mp3", ".mp4", ".mkv", ".mov", ".avi", ".wav", ".flac", ".ogg",
    ".woff", ".woff2", ".ttf", ".otf", ".eot",
    ".apk", ".aab", ".ipa", ".jar", ".class", ".pyc", ".whl",
    ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
    ".sqlite", ".db",
}

_VOLUME_PREFIXES = ("/mnt/", "/media/", "/run/media/")
_SKIP_FS = {
    "proc", "sysfs", "devpts", "tmpfs", "devtmpfs", "cgroup", "cgroup2",
    "pstore", "bpf", "tracefs", "debugfs", "securityfs", "overlay",
    "squashfs", "autofs", "binfmt_misc", "configfs", "fusectl", "mqueue",
    "hugetlbfs", "ramfs", "nsfs", "rpc_pipefs", "nfsd",
}
_RESERVED_WINDOWS = (
    {"CON", "PRN", "AUX", "NUL"}
    | {f"COM{i}" for i in range(1, 10)}
    | {f"LPT{i}" for i in range(1, 10)}
)


class FileAccessError(Exception):
    def __init__(self, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class TextDocument:
    path: Path
    name: str
    content: str
    size: int


@dataclass(frozen=True)
class RootBound:
    resolved: Path
    lexical: tuple[Path, ...]


@dataclass(frozen=True)
class Located:
    lexical: Path
    real: Path
    root: Path
    is_link: bool


def select_drive_letters(bitmask: int, kind_of: Callable[[str], int]) -> list[str]:
    """Keep fixed, removable, and mapped drives. Skip empty slots and CD-ROMs."""
    found: list[str] = []
    for index, letter in enumerate(string.ascii_uppercase):
        if not bitmask & (1 << index):
            continue
        root = f"{letter}:\\"
        try:
            kind = int(kind_of(root))
        except (OSError, TypeError, ValueError):
            continue
        if kind in _INCLUDED_DRIVE_TYPES:
            found.append(root)
    return found


def detect_drives() -> list[Path]:
    """Fixed and removable volumes on this machine, as of this call."""
    if os.name == "nt":
        return [Path(item) for item in _windows_drive_roots()]
    return [Path(item) for item in posix_volume_paths(read_mounts())]


def _windows_drive_roots() -> list[str]:
    import ctypes

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    bitmask = int(kernel.GetLogicalDrives())

    def kind_of(root: str) -> int:
        return int(kernel.GetDriveTypeW(root))

    return select_drive_letters(bitmask, kind_of)


def read_mounts() -> str:
    try:
        return Path("/proc/mounts").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def posix_volume_paths(mounts_text: str) -> list[str]:
    """`/` plus mounted disks under /mnt, /media, and /run/media."""
    volumes = ["/"]
    for line in mounts_text.splitlines():
        parts = line.split()
        if len(parts) < 3:
            continue
        mountpoint = _unescape_mount(parts[1])
        fstype = parts[2]
        if fstype in _SKIP_FS or mountpoint == "/":
            continue
        if not mountpoint.startswith(_VOLUME_PREFIXES):
            continue
        if mountpoint not in volumes:
            volumes.append(mountpoint)
    return volumes


def _unescape_mount(value: str) -> str:
    return re.sub(r"\\([0-7]{3})", lambda match: chr(int(match.group(1), 8)), value)


def lexical_normalize(raw: str) -> Path:
    if not isinstance(raw, str):
        raise FileAccessError("Use a full path on the PC.", 400)
    text = raw.strip()
    if not text or len(text) > 32000:
        raise FileAccessError("That path is not valid.", 400)
    if any(ord(ch) < 32 for ch in text):
        raise FileAccessError("That path is not valid.", 400)
    if text.startswith("\\\\") or text.startswith("//"):
        raise FileAccessError(
            "Desk opens drives on this PC, not a network path written as \\\\server\\share.",
            400,
        )
    path = Path(text)
    if not path.is_absolute():
        raise FileAccessError(
            "Use a full path on the PC, such as C:\\Users\\Austin\\Desktop.",
            400,
        )
    anchor = path.anchor
    if os.name == "nt" and len(anchor) >= 2 and anchor[1] == ":":
        anchor = anchor[0].upper() + anchor[1:]
    parts: list[str] = []
    for part in path.parts[1:]:
        if part in {".", ""}:
            continue
        if part == "..":
            if not parts:
                raise FileAccessError(OUTSIDE, 403)
            parts.pop()
            continue
        if "/" in part or "\\" in part or part in {"/", "\\"}:
            raise FileAccessError("That path is not valid.", 400)
        parts.append(part)
    if not parts:
        return Path(anchor)
    return Path(anchor).joinpath(*parts)


def is_within(path: Path, root: Path) -> bool:
    if _within_one(path, root):
        return True
    if os.name == "nt":
        return _within_one(Path(os.path.normcase(str(path))), Path(os.path.normcase(str(root))))
    return False


def _within_one(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return True


def safe_filename(name: str) -> str:
    if not isinstance(name, str):
        raise FileAccessError("That file name is not valid.", 400)
    cleaned = name.replace("\\", "/").split("/")[-1].strip().rstrip(". ")
    if not cleaned or cleaned in {".", ".."}:
        raise FileAccessError("That file name is not valid.", 400)
    if any(ord(ch) < 32 for ch in cleaned) or "/" in cleaned or "\\" in cleaned or ":" in cleaned:
        raise FileAccessError("That file name is not valid.", 400)
    stem = cleaned.split(".", 1)[0].upper()
    if stem in _RESERVED_WINDOWS:
        raise FileAccessError("That file name is reserved by Windows.", 400)
    if len(cleaned) > 180:
        raise FileAccessError("That file name is too long.", 400)
    return cleaned


def is_text_name(name: str) -> bool:
    suffix = Path(name).suffix.lower()
    if suffix in BINARY_EXTENSIONS:
        return False
    if suffix in TEXT_EXTENSIONS or name.lower() in TEXT_NAMES:
        return True
    return suffix == "" or suffix not in BINARY_EXTENSIONS


def looks_textual(path: Path) -> bool:
    suffix = path.suffix.lower()
    if suffix in BINARY_EXTENSIONS:
        return False
    try:
        with path.open("rb") as handle:
            sample = handle.read(8192)
    except OSError:
        return False
    if b"\x00" in sample:
        return False
    if suffix in TEXT_EXTENSIONS or path.name.lower() in TEXT_NAMES:
        return True
    if not sample:
        return True
    try:
        sample.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def drive_label(path: Path) -> str:
    match = re.match(r"^([A-Za-z]:)[\\/]*$", str(path))
    if match:
        return match.group(1).upper()
    if path == Path("/"):
        return "Computer"
    return path.name or str(path)


def _lexical_forms(original: Path, resolved: Path) -> tuple[Path, ...]:
    forms: list[Path] = []
    for candidate in (original, resolved):
        try:
            forms.append(lexical_normalize(str(candidate)))
        except FileAccessError:
            continue
    unique: list[Path] = []
    for form in forms:
        if form not in unique:
            unique.append(form)
    return tuple(unique)


def _os_message(exc: OSError) -> str:
    detail = exc.strerror or str(exc)
    return detail[:240]


class FileService:
    def __init__(
        self,
        allowed_roots: tuple[str, ...] = (),
        *,
        max_upload_bytes: int = 200 * 1024 * 1024,
        max_text_bytes: int = 1_000_000,
        detector: Callable[[], list[Path]] | None = None,
    ) -> None:
        self.allowed_roots = allowed_roots or (str(Path.home() / "Desk"),)
        self.max_upload_bytes = max_upload_bytes
        self.max_text_bytes = max_text_bytes
        self._detector = detector or detect_drives

    def is_all(self) -> bool:
        return len(self.allowed_roots) == 1 and self.allowed_roots[0].strip().upper() == "ALL"

    def bounds(self) -> list[RootBound]:
        if self.is_all():
            return self._bounds_from_detected()
        return self._bounds_from_config()

    def roots_payload(self) -> dict[str, object]:
        items = []
        for bound in self.bounds():
            try:
                writable = os.access(bound.resolved, os.W_OK)
            except OSError:
                writable = False
            items.append(
                {
                    "path": str(bound.resolved),
                    "label": drive_label(bound.resolved),
                    "writable": writable,
                }
            )
        items.sort(key=lambda item: str(item["label"]).casefold())
        return {"mode": "all" if self.is_all() else "restricted", "roots": items}

    def list_dir(self, raw: str) -> dict[str, object]:
        bounds = self.bounds()
        located = self.locate(raw, bounds, must_exist=True, kind="dir")
        directory = located.real
        rows: list[dict[str, object]] = []
        truncated = False
        try:
            children = directory.iterdir()
        except PermissionError as exc:
            raise FileAccessError("Desk cannot read that folder.", 403) from exc
        except OSError as exc:
            raise FileAccessError(f"Desk cannot read that folder. {_os_message(exc)}", 403) from exc
        for index, child in enumerate(children):
            if index >= _LIST_CAP:
                truncated = True
                break
            described = self._describe(child, bounds)
            if described is not None:
                rows.append(described)
        rows.sort(key=lambda row: (row["kind"] != "dir", str(row["name"]).casefold()))
        return {
            "path": str(directory),
            "parent": self._parent_pointer(directory, bounds),
            "truncated": truncated,
            "entries": rows,
        }

    def read_text(self, raw: str) -> TextDocument:
        bounds = self.bounds()
        located = self.locate(raw, bounds, must_exist=True, kind="file")
        path = located.real
        try:
            size = path.stat().st_size
        except OSError as exc:
            raise FileAccessError("That file is not on the PC.", 404) from exc
        if size > self.max_text_bytes:
            raise FileAccessError(
                "This file is too large to edit here "
                f"(limit is {self.max_text_bytes} bytes). You can still download it.",
                413,
            )
        if not looks_textual(path):
            raise FileAccessError(
                "Desk can edit text files. You can still download this one.",
                415,
            )
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise FileAccessError(f"Desk could not read that file. {_os_message(exc)}", 403) from exc
        if b"\x00" in data:
            raise FileAccessError(
                "Desk can edit text files. You can still download this one.",
                415,
            )
        try:
            content = data.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise FileAccessError(
                "Desk could not read that file as text. You can still download it.",
                415,
            ) from exc
        return TextDocument(path=path, name=path.name, content=content, size=len(data))

    def file_for_download(self, raw: str) -> Path:
        located = self.locate(raw, self.bounds(), must_exist=True, kind="file")
        return located.real

    def write_text(self, raw: str, content: str) -> dict[str, object]:
        if not isinstance(content, str):
            raise FileAccessError("The file contents need to be text.", 400)
        if "\x00" in content:
            raise FileAccessError("Desk can only save text files.", 415)
        data = content.encode("utf-8")
        if len(data) > self.max_text_bytes:
            raise FileAccessError(
                f"That text is larger than the {self.max_text_bytes} byte editing limit.",
                413,
            )
        bounds = self.bounds()
        lexical = lexical_normalize(raw)
        if not self._lexical_inside(lexical, bounds):
            raise FileAccessError(OUTSIDE, 403)
        try:
            exists = lexical.exists() or lexical.is_symlink()
        except OSError as exc:
            raise FileAccessError(f"Desk cannot open that path. {_os_message(exc)}", 403) from exc
        if exists:
            located = self.locate(raw, bounds, must_exist=True, kind="file")
            if not looks_textual(located.real):
                raise FileAccessError(
                    "Desk can only edit text files. You can still download this one.",
                    415,
                )
            current = located.real.read_bytes()
            if current == data:
                return {
                    "path": str(located.real),
                    "bytes": len(data),
                    "backup": None,
                    "unchanged": True,
                }
            backup = self._backup(located.real)
            self._atomic_write(located.real, data)
            return {
                "path": str(located.real),
                "bytes": len(data),
                "backup": str(backup),
                "unchanged": False,
            }
        if not is_text_name(lexical.name):
            raise FileAccessError(
                "Desk can only create text files here. Upload other files from the phone.",
                415,
            )
        name = safe_filename(lexical.name)
        parent = self.locate(str(lexical.parent), bounds, must_exist=True, kind="dir")
        dest = parent.real / name
        if self._real_root(dest, bounds) is None:
            raise FileAccessError(OUTSIDE, 403)
        if dest.exists() or dest.is_symlink():
            raise FileAccessError("That file already exists.", 409)
        self._atomic_write(dest, data)
        return {
            "path": str(dest),
            "bytes": len(data),
            "backup": None,
            "unchanged": False,
            "created": True,
        }

    async def save_upload(self, directory: str, filename: str, stream: AsyncIterator[bytes]) -> dict[str, object]:
        bounds = self.bounds()
        folder = self.locate(directory, bounds, must_exist=True, kind="dir")
        name = safe_filename(filename or "upload")
        dest = self._unique_name(folder.real, name)
        if self._real_root(dest, bounds) is None:
            raise FileAccessError(OUTSIDE, 403)
        tmp = dest.with_name(f".{dest.name}.{uuid.uuid4().hex}.upload")
        total = 0
        try:
            with tmp.open("wb") as handle:
                async for chunk in stream:
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > self.max_upload_bytes:
                        limit_mb = max(1, self.max_upload_bytes // (1024 * 1024))
                        raise FileAccessError(
                            f"That file is larger than the {limit_mb} MB upload limit.",
                            413,
                        )
                    handle.write(chunk)
            os.replace(tmp, dest)
        except FileAccessError:
            raise
        except OSError as exc:
            raise FileAccessError(f"Desk could not save that upload. {_os_message(exc)}", 403) from exc
        finally:
            try:
                if tmp.exists() or tmp.is_symlink():
                    tmp.unlink()
            except OSError:
                pass
        return {"path": str(dest), "name": dest.name, "bytes": total}

    def make_dir(self, raw: str) -> dict[str, object]:
        bounds = self.bounds()
        lexical = lexical_normalize(raw)
        if not self._lexical_inside(lexical, bounds):
            raise FileAccessError(OUTSIDE, 403)
        try:
            if lexical.exists() or lexical.is_symlink():
                raise FileAccessError("That folder already exists.", 409)
        except FileAccessError:
            raise
        except OSError as exc:
            raise FileAccessError(f"Desk cannot open that path. {_os_message(exc)}", 403) from exc
        name = safe_filename(lexical.name)
        parent = self.locate(str(lexical.parent), bounds, must_exist=True, kind="dir")
        dest = parent.real / name
        if self._real_root(dest, bounds) is None:
            raise FileAccessError(OUTSIDE, 403)
        try:
            dest.mkdir()
        except FileExistsError as exc:
            raise FileAccessError("That folder already exists.", 409) from exc
        except OSError as exc:
            raise FileAccessError(f"Desk could not create that folder. {_os_message(exc)}", 403) from exc
        return {"path": str(dest), "name": dest.name}

    def rename(self, raw: str, new_name: str) -> dict[str, object]:
        bounds = self.bounds()
        located = self.locate(raw, bounds, must_exist=True, kind="any")
        source = located.lexical if located.is_link else located.real
        if source == located.root:
            raise FileAccessError("Choose a file or folder inside the drive.", 400)
        name = safe_filename(new_name)
        dest = source.parent / name
        if self._real_root(dest, bounds) is None or not self._lexical_inside(lexical_normalize(str(dest)), bounds):
            raise FileAccessError(OUTSIDE, 403)
        if dest.exists() or dest.is_symlink():
            raise FileAccessError("A file with that name is already there.", 409)
        try:
            os.rename(source, dest)
        except OSError as exc:
            raise FileAccessError(f"Desk could not rename that item. {_os_message(exc)}", 403) from exc
        return {"path": str(dest), "name": dest.name}

    def delete(self, raw: str) -> dict[str, object]:
        bounds = self.bounds()
        lexical = lexical_normalize(raw)
        if not self._lexical_inside(lexical, bounds):
            raise FileAccessError(OUTSIDE, 403)
        try:
            is_link = lexical.is_symlink()
        except OSError as exc:
            raise FileAccessError(f"Desk cannot open that path. {_os_message(exc)}", 403) from exc
        if is_link:
            try:
                parent_real = lexical.parent.resolve(strict=True)
            except OSError as exc:
                raise FileAccessError("That folder is not on the PC.", 404) from exc
            root = self._real_root(parent_real, bounds)
            if root is None:
                raise FileAccessError(OUTSIDE, 403)
            trashed = self._trash(lexical, root)
            return {"path": str(lexical), "trashed": str(trashed)}
        located = self.locate(raw, bounds, must_exist=True, kind="any")
        trashed = self._trash(located.real, located.root)
        return {"path": str(located.real), "trashed": str(trashed)}

    def locate(self, raw: str, bounds: list[RootBound], *, must_exist: bool, kind: str) -> Located:
        lexical = lexical_normalize(raw)
        if not self._lexical_inside(lexical, bounds):
            raise FileAccessError(OUTSIDE, 403)
        try:
            is_link = lexical.is_symlink()
        except OSError as exc:
            raise FileAccessError(f"Desk cannot open that path. {_os_message(exc)}", 403) from exc
        if is_link:
            return self._locate_link(lexical, bounds, must_exist=must_exist, kind=kind)
        try:
            exists = lexical.exists()
        except OSError as exc:
            raise FileAccessError(f"Desk cannot open that path. {_os_message(exc)}", 403) from exc
        if exists:
            try:
                real = lexical.resolve(strict=True)
            except OSError as exc:
                raise FileAccessError("That file or folder is not on the PC.", 404) from exc
            root = self._real_root(real, bounds)
            if root is None:
                raise FileAccessError(OUTSIDE, 403)
            if must_exist:
                self._check_kind(real, kind)
            return Located(lexical=lexical, real=real, root=root, is_link=False)
        if must_exist:
            raise FileAccessError("That file or folder is not on the PC.", 404)
        parent_lexical = lexical.parent
        if parent_lexical == lexical:
            raise FileAccessError("That file or folder is not on the PC.", 404)
        parent = self.locate(str(parent_lexical), bounds, must_exist=True, kind="dir")
        real = parent.real / lexical.name
        if self._real_root(real, bounds) is None:
            raise FileAccessError(OUTSIDE, 403)
        return Located(lexical=lexical, real=real, root=parent.root, is_link=False)

    def _bounds_from_detected(self) -> list[RootBound]:
        try:
            detected = list(self._detector())
        except OSError as exc:
            raise FileAccessError("Desk could not list the drives on this PC.", 503) from exc
        bounds: list[RootBound] = []
        for item in detected:
            try:
                if not item.exists() or not item.is_dir():
                    continue
                resolved = item.resolve()
            except OSError:
                continue
            if not resolved.is_dir():
                continue
            forms = _lexical_forms(item, resolved)
            if not forms:
                continue
            bounds.append(RootBound(resolved=resolved, lexical=forms))
        if not bounds:
            raise FileAccessError(
                "Desk could not find any drives on this PC. Check that a disk is connected.",
                503,
            )
        return _dedupe(bounds)

    def _bounds_from_config(self) -> list[RootBound]:
        bounds: list[RootBound] = []
        for raw in self.allowed_roots:
            text = raw.strip()
            if not text or text.upper() == "ALL":
                continue
            path = Path(text).expanduser()
            if not path.is_absolute():
                continue
            try:
                if not path.exists():
                    path.mkdir(parents=True, exist_ok=True)
                resolved = path.resolve()
            except OSError:
                continue
            if not resolved.is_dir():
                continue
            forms = _lexical_forms(path, resolved)
            if not forms:
                continue
            bounds.append(RootBound(resolved=resolved, lexical=forms))
        if not bounds:
            raise FileAccessError(
                "None of the folders in ALLOWED_ROOTS are available. Check config/.env on the PC.",
                503,
            )
        return _dedupe(bounds)

    def _locate_link(
        self,
        lexical: Path,
        bounds: list[RootBound],
        *,
        must_exist: bool,
        kind: str,
    ) -> Located:
        try:
            real_parent = lexical.parent.resolve(strict=True)
        except OSError as exc:
            raise FileAccessError("That folder is not on the PC.", 404) from exc
        if self._real_root(real_parent, bounds) is None:
            raise FileAccessError(OUTSIDE, 403)
        try:
            target = lexical.resolve(strict=True)
        except OSError:
            target = None
        if target is None:
            if must_exist:
                raise FileAccessError("That shortcut is broken.", 404)
            root = self._real_root(real_parent, bounds)
            assert root is not None
            return Located(
                lexical=lexical,
                real=real_parent / lexical.name,
                root=root,
                is_link=True,
            )
        root = self._real_root(target, bounds)
        if root is None:
            raise FileAccessError("That shortcut points outside the drives Desk can use.", 403)
        if must_exist:
            self._check_kind(target, kind)
        return Located(lexical=lexical, real=target, root=root, is_link=True)

    def _describe(self, child: Path, bounds: list[RootBound]) -> dict[str, object] | None:
        name = child.name
        if name in {".", ".."}:
            return None
        try:
            link = child.is_symlink()
        except OSError:
            return None
        if link:
            try:
                target = child.resolve(strict=True)
            except OSError:
                return self._blocked(child)
            if self._real_root(target, bounds) is None:
                return self._blocked(child)
            described_path = target
        else:
            described_path = child
        try:
            if not described_path.exists():
                return None
            is_dir = described_path.is_dir()
            stat = described_path.stat()
        except OSError:
            return None
        size = None if is_dir else int(stat.st_size)
        text = False
        if not is_dir and size is not None and size <= self.max_text_bytes:
            text = looks_textual(described_path)
        return {
            "name": name,
            "path": str(child if link else described_path),
            "kind": "dir" if is_dir else "file",
            "size": size,
            "modified": int(stat.st_mtime),
            "text": text,
            "link": link,
            "blocked": False,
        }

    def _blocked(self, child: Path) -> dict[str, object]:
        return {
            "name": child.name,
            "path": str(child),
            "kind": "file",
            "size": None,
            "modified": None,
            "text": False,
            "link": True,
            "blocked": True,
        }

    def _parent_pointer(self, directory: Path, bounds: list[RootBound]) -> str | None:
        if any(directory == bound.resolved for bound in bounds):
            return None
        parent = directory.parent
        if parent == directory or self._real_root(parent, bounds) is None:
            return None
        return str(parent)

    def _backup(self, path: Path) -> Path:
        backup = self._backup_destination(path)
        if self._real_root(backup, self.bounds()) is None and self._real_root(backup.parent, self.bounds()) is None:
            raise FileAccessError("Desk could not write a backup next to that file.", 403)
        try:
            shutil.copy2(path, backup)
        except OSError as exc:
            raise FileAccessError(f"Desk could not back up that file. {_os_message(exc)}", 403) from exc
        return backup

    def _backup_destination(self, path: Path) -> Path:
        primary = path.with_name(path.name + ".bak")
        if not primary.exists() and not primary.is_symlink():
            return primary
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        candidate = path.with_name(f"{path.name}.{stamp}.bak")
        number = 1
        while candidate.exists() or candidate.is_symlink():
            candidate = path.with_name(f"{path.name}.{stamp}-{number}.bak")
            number += 1
            if number > 50:
                raise FileAccessError("Desk could not find a free name for the backup.", 409)
        return candidate

    def _atomic_write(self, path: Path, data: bytes) -> None:
        tmp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        try:
            with tmp.open("xb") as handle:
                handle.write(data)
            os.replace(tmp, path)
        except OSError as exc:
            raise FileAccessError(f"Desk could not save that file. {_os_message(exc)}", 403) from exc
        finally:
            try:
                if tmp.exists() or tmp.is_symlink():
                    tmp.unlink()
            except OSError:
                pass

    def _unique_name(self, directory: Path, name: str) -> Path:
        candidate = directory / name
        if not candidate.exists() and not candidate.is_symlink():
            return candidate
        stem = Path(name).stem
        suffix = Path(name).suffix
        for number in range(2, 1000):
            candidate = directory / f"{stem} ({number}){suffix}"
            if not candidate.exists() and not candidate.is_symlink():
                return candidate
        raise FileAccessError("Too many files already use that name.", 409)

    def _trash(self, source: Path, root: Path) -> Path:
        if source == root:
            raise FileAccessError("Choose a file or folder on the drive, not the drive itself.", 400)
        trash = root / TRASH_NAME
        if source == trash or _is_relative_to(source, trash):
            raise FileAccessError("That item is already in the trash.", 400)
        if _is_relative_to(trash, source):
            raise FileAccessError("That folder holds the trash folder, so Desk will not move it.", 400)
        try:
            trash.mkdir(exist_ok=True)
        except OSError as exc:
            raise FileAccessError(f"Desk could not create the trash folder. {_os_message(exc)}", 403) from exc
        try:
            relative = source.relative_to(root)
        except ValueError as exc:
            raise FileAccessError(OUTSIDE, 403) from exc
        dest = trash.joinpath(relative)
        try:
            dest.parent.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise FileAccessError(f"Desk could not create the trash folder. {_os_message(exc)}", 403) from exc
        if dest.exists() or dest.is_symlink():
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
            dest = dest.with_name(f"{dest.name}.{stamp}")
            number = 1
            while dest.exists() or dest.is_symlink():
                dest = dest.with_name(f"{dest.name}.{stamp}-{number}")
                number += 1
                if number > 50:
                    raise FileAccessError("Desk could not find a free name in the trash.", 409)
        try:
            shutil.move(str(source), str(dest))
        except OSError as exc:
            raise FileAccessError(f"Desk could not move that item to the trash. {_os_message(exc)}", 403) from exc
        return dest

    def _lexical_inside(self, lexical: Path, bounds: list[RootBound]) -> bool:
        return any(is_within(lexical, form) for bound in bounds for form in bound.lexical)

    def _real_root(self, real: Path, bounds: list[RootBound]) -> Path | None:
        winner: Path | None = None
        winner_len = -1
        for bound in bounds:
            if is_within(real, bound.resolved) and len(bound.resolved.parts) > winner_len:
                winner = bound.resolved
                winner_len = len(bound.resolved.parts)
        return winner

    def _check_kind(self, path: Path, kind: str) -> None:
        if kind == "dir" and not path.is_dir():
            raise FileAccessError("That path is a file. Open a folder.", 400)
        if kind == "file" and not path.is_file():
            raise FileAccessError("That path is a folder.", 400)


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return path != root


def _dedupe(bounds: list[RootBound]) -> list[RootBound]:
    kept: list[RootBound] = []
    seen: set[Path] = set()
    for bound in bounds:
        if bound.resolved in seen:
            continue
        seen.add(bound.resolved)
        kept.append(bound)
    return kept

