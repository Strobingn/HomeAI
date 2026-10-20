"""Path safety and file endpoints. Ollama is the same stand-in as the chat tests."""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import parse_allowed_roots
from app.file_ai import extract_proposed, select_file_model
from app.files import (
    FileAccessError,
    FileService,
    detect_drives,
    lexical_normalize,
    posix_volume_paths,
    safe_filename,
    select_drive_letters,
)
from tests.test_api import AUTH, FakeOllama, _app

# CD-ROM is not a drive Desk should treat as a working disk.
DRIVE_CDROM = 5


def service_for(root: Path, **kwargs: object) -> FileService:
    return FileService((str(root),), **kwargs)  # type: ignore[arg-type]


def test_all_mode_is_the_default_and_can_be_narrowed() -> None:
    assert parse_allowed_roots(None) == ("ALL",)
    assert parse_allowed_roots("") == ("ALL",)
    assert parse_allowed_roots(" all ") == ("ALL",)
    assert parse_allowed_roots(r"C:\Users\Austin\Desktop; D:\Work") == (
        r"C:\Users\Austin\Desktop",
        r"D:\Work",
    )


def test_drive_letters_keep_fixed_removable_and_mapped() -> None:
    bitmask = (1 << 0) | (1 << 2) | (1 << 3) | (1 << 5)
    kinds = {"A:\\": 2, "C:\\": 3, "D:\\": DRIVE_CDROM, "F:\\": 4}
    assert select_drive_letters(bitmask, kinds.__getitem__) == ["A:\\", "C:\\", "F:\\"]
    assert select_drive_letters(0, lambda _root: 3) == []


def test_posix_volumes_are_the_linux_stand_in_for_drives() -> None:
    text = "\n".join(
        [
            "/dev/sda1 / ext4 rw 0 0",
            "proc /proc proc rw 0 0",
            "/dev/sdb1 /mnt/usb ext4 rw 0 0",
            "tmpfs /run tmpfs rw 0 0",
            "/dev/sdc1 /media/Austin/Backup ntfs rw 0 0",
            "/dev/sdd1 /var/lib/docker ext4 rw 0 0",
        ]
    )
    assert posix_volume_paths(text) == ["/", "/mnt/usb", "/media/Austin/Backup"]


def test_detect_drives_includes_the_filesystem_root_on_linux() -> None:
    if __import__("os").name == "nt":
        letters = {str(path)[:2].upper() for path in detect_drives()}
        assert "C:" in letters or letters
        return
    assert Path("/") in detect_drives()


def test_all_mode_lists_every_detected_drive_and_rereads_them(tmp_path: Path) -> None:
    first = tmp_path / "c-drive"
    second = tmp_path / "d-drive"
    first.mkdir()
    state = {"drives": [first]}

    def detector() -> list[Path]:
        return list(state["drives"])

    files = FileService(("ALL",), detector=detector)
    payload = files.roots_payload()
    assert payload["mode"] == "all"
    assert [item["path"] for item in payload["roots"]] == [str(first.resolve())]  # type: ignore[index]

    second.mkdir()
    (second / "note.txt").write_text("on D", encoding="utf-8")
    state["drives"] = [first, second]
    paths = [item["path"] for item in files.roots_payload()["roots"]]  # type: ignore[index]
    assert str(second.resolve()) in paths
    assert files.read_text(str(second / "note.txt")).content == "on D"
    files.write_text(str(first / "made.txt"), "written")
    assert (first / "made.txt").read_text(encoding="utf-8") == "written"


def test_all_mode_still_refuses_a_path_on_a_drive_that_is_not_there(tmp_path: Path) -> None:
    allowed = tmp_path / "only"
    other = tmp_path / "other"
    allowed.mkdir()
    other.mkdir()
    (other / "secret.txt").write_text("no", encoding="utf-8")
    files = FileService(("ALL",), detector=lambda: [allowed])
    with pytest.raises(FileAccessError) as exc:
        files.read_text(str(other / "secret.txt"))
    assert exc.value.status == 403


def test_explicit_root_is_created_and_a_sibling_prefix_is_refused(tmp_path: Path) -> None:
    root = tmp_path / "allowed"
    missing = tmp_path / "created" / "workspace"
    evil = tmp_path / "allowed-evil"
    evil.mkdir()
    (evil / "x.txt").write_text("nope", encoding="utf-8")
    files = FileService((str(root), str(missing)))
    payload = files.roots_payload()
    assert payload["mode"] == "restricted"
    assert root.is_dir()
    assert missing.is_dir()
    with pytest.raises(FileAccessError) as exc:
        files.read_text(str(evil / "x.txt"))
    assert exc.value.status == 403


def test_dotdot_null_and_relative_paths_are_refused(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    secret = tmp_path / "secret.txt"
    secret.write_text("hidden", encoding="utf-8")
    files = service_for(root)
    with pytest.raises(FileAccessError) as traversed:
        files.read_text(str(root / ".." / "secret.txt"))
    assert traversed.value.status == 403
    with pytest.raises(FileAccessError):
        files.read_text(str(root / "a") + "\x00.txt")
    with pytest.raises(FileAccessError) as relative:
        files.read_text("notes.txt")
    assert relative.value.status == 400
    assert lexical_normalize(str(root / "sub" / ".." / "file.txt")) == root / "file.txt"


def test_symlink_escape_is_refused_for_read_list_and_upload(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    outside = tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "secret.txt").write_text("hidden", encoding="utf-8")
    (root / "hole").symlink_to(outside, target_is_directory=True)
    (root / "leak.txt").symlink_to(outside / "secret.txt")
    files = service_for(root)

    with pytest.raises(FileAccessError) as read_dir:
        files.read_text(str(root / "hole" / "secret.txt"))
    assert read_dir.value.status == 403
    with pytest.raises(FileAccessError) as read_file:
        files.read_text(str(root / "leak.txt"))
    assert read_file.value.status == 403

    listing = files.list_dir(str(root))
    blocked = [item["name"] for item in listing["entries"] if item["blocked"]]  # type: ignore[index]
    assert "hole" in blocked
    assert "leak.txt" in blocked
    assert "secret.txt" not in [item["name"] for item in listing["entries"]]  # type: ignore[index]

    with pytest.raises(FileAccessError):
        import asyncio

        asyncio.run(
            files.save_upload(
                str(root / "hole"),
                "note.txt",
                _stream(b"nope"),
            )
        )
    assert not (outside / "note.txt").exists()

    files.delete(str(root / "leak.txt"))
    assert not (root / "leak.txt").exists()
    assert (outside / "secret.txt").read_text(encoding="utf-8") == "hidden"


def test_symlink_inside_the_same_drive_can_be_read(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    folder = root / "real"
    folder.mkdir(parents=True)
    (folder / "note.txt").write_text("hello", encoding="utf-8")
    (root / "alias").symlink_to(folder, target_is_directory=True)
    files = service_for(root)
    assert files.read_text(str(root / "alias" / "note.txt")).content == "hello"


def test_save_writes_a_bak_and_a_later_save_keeps_it(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    target = root / "notes.txt"
    target.write_text("hello", encoding="utf-8")
    files = service_for(root)
    first = files.write_text(str(target), "world")
    assert target.read_text(encoding="utf-8") == "world"
    assert Path(str(first["backup"])).read_text(encoding="utf-8") == "hello"
    assert first["backup"].endswith("notes.txt.bak")  # type: ignore[union-attr]
    second = files.write_text(str(target), "again")
    assert target.read_text(encoding="utf-8") == "again"
    assert (root / "notes.txt.bak").read_text(encoding="utf-8") == "hello"
    assert Path(str(second["backup"])).read_text(encoding="utf-8") == "world"
    same = files.write_text(str(target), "again")
    assert same["unchanged"] is True
    assert same["backup"] is None


def test_delete_moves_into_trash_and_will_not_remove_the_trash(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    folder = root / "docs"
    folder.mkdir(parents=True)
    target = folder / "note.txt"
    target.write_text("keep me", encoding="utf-8")
    files = service_for(root)
    removed = files.delete(str(target))
    assert not target.exists()
    trashed = Path(str(removed["trashed"]))
    assert trashed.is_file()
    assert ".trash" in trashed.parts
    assert trashed.read_text(encoding="utf-8") == "keep me"
    with pytest.raises(FileAccessError) as again:
        files.delete(str(trashed))
    assert again.value.status == 400
    with pytest.raises(FileAccessError):
        files.delete(str(root / ".trash"))
    with pytest.raises(FileAccessError):
        files.delete(str(root))
    nested = root / "box"
    nested.mkdir()
    (nested / "inside.txt").write_text("box", encoding="utf-8")
    files.delete(str(nested))
    assert not nested.exists()
    assert (root / ".trash" / "box" / "inside.txt").read_text(encoding="utf-8") == "box"


def test_upload_name_cannot_escape_the_folder(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    files = service_for(root, max_upload_bytes=32)

    import asyncio

    saved = asyncio.run(files.save_upload(str(root), r"..\..\..\evil.txt", _stream(b"safe")))
    assert Path(str(saved["path"])) == root / "evil.txt"
    assert not (tmp_path / "evil.txt").exists()
    with pytest.raises(FileAccessError) as too_big:
        asyncio.run(files.save_upload(str(root), "big.bin", _stream(b"x" * 64)))
    assert too_big.value.status == 413
    assert not (root / "big.bin").exists()
    assert safe_filename(r"folder\notes.txt") == "notes.txt"


def test_file_routes_require_the_key_and_round_trip(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    app, _fake = _app(tmp_path, file_service=service_for(root))
    with TestClient(app) as client:
        assert client.get("/files/roots").status_code == 401
        roots = client.get("/files/roots", headers=AUTH)
        assert roots.status_code == 200
        assert roots.json()["mode"] == "restricted"
        created = client.post("/files/folder", headers=AUTH, json={"path": str(root / "Inbox")})
        assert created.status_code == 200
        uploaded = client.post(
            "/files/upload",
            headers=AUTH,
            data={"directory": str(root / "Inbox")},
            files=[
                ("file", ("a.txt", b"alpha", "text/plain")),
                ("file", ("b.txt", b"beta", "text/plain")),
            ],
        )
        assert uploaded.status_code == 200
        assert {item["name"] for item in uploaded.json()["saved"]} == {"a.txt", "b.txt"}
        listing = client.get("/files/list", headers=AUTH, params={"path": str(root / "Inbox")})
        assert listing.status_code == 200
        assert listing.json()["parent"] == str(root.resolve())
        names = [item["name"] for item in listing.json()["entries"]]
        assert names == ["a.txt", "b.txt"]
        read = client.get("/files/read", headers=AUTH, params={"path": str(root / "Inbox" / "a.txt")})
        assert read.json()["content"] == "alpha"
        renamed = client.post(
            "/files/rename",
            headers=AUTH,
            json={"path": str(root / "Inbox" / "b.txt"), "name": "c.txt"},
        )
        assert renamed.status_code == 200
        assert (root / "Inbox" / "c.txt").read_text(encoding="utf-8") == "beta"
        downloaded = client.get(
            "/files/download",
            headers=AUTH,
            params={"path": str(root / "Inbox" / "a.txt")},
        )
        assert downloaded.status_code == 200
        assert downloaded.content == b"alpha"
        assert "a.txt" in downloaded.headers["content-disposition"]
        picture = root / "pic.png"
        picture.write_bytes(b"\x89PNG\r\n\x1a\n\x00\x00")
        refused = client.get("/files/read", headers=AUTH, params={"path": str(picture)})
        assert refused.status_code == 415
        fetched = client.get("/files/download", headers=AUTH, params={"path": str(picture)})
        assert fetched.content.startswith(b"\x89PNG")
        outside = client.get("/files/read", headers=AUTH, params={"path": str(tmp_path / "nope.txt")})
        assert outside.status_code == 403
        assert "outside" in outside.json()["detail"].lower() or "ALLOWED_ROOTS" in outside.json()["detail"]


def test_binary_extension_and_new_text_file(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    files = service_for(root)
    (root / "README").write_text("hi", encoding="utf-8")
    assert files.read_text(str(root / "README")).content == "hi"
    made = files.write_text(str(root / "fresh.txt"), "new")
    assert made["created"] is True
    assert (root / "fresh.txt").read_text(encoding="utf-8") == "new"
    with pytest.raises(FileAccessError) as binary:
        files.write_text(str(root / "app.exe"), "nope")
    assert binary.value.status == 415


def test_ai_streams_a_proposal_and_does_not_save_it(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    script = root / "main.py"
    script.write_text("print('old')\n", encoding="utf-8")
    fake = FakeOllama()
    fake.installed = ["qwen2.5:7b", "qwen2.5-coder:14b"]
    fake.tokens = ["Adding a line.\n", "```file\n", "print('old')\nprint('new')\n", "```"]
    app, fake = _app(tmp_path, fake, file_service=service_for(root))
    with TestClient(app) as client:
        response = client.post(
            "/files/ai",
            headers=AUTH,
            json={"path": str(script), "instruction": "Add a second print."},
        )
    assert response.status_code == 200
    assert "print('new')" in response.text
    assert '"done":true' in response.text
    assert "qwen2.5-coder:14b" in response.text
    assert script.read_text(encoding="utf-8") == "print('old')\n"
    assert fake.chat_bodies[-1]["model"] == "qwen2.5-coder:14b"
    assert fake.chat_bodies[-1]["options"]["temperature"] == 0.2
    assert "print('old')" in fake.chat_bodies[-1]["messages"][-1]["content"]


def test_ai_uses_the_default_model_when_the_coder_model_is_missing(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    script = root / "main.py"
    script.write_text("x = 1\n", encoding="utf-8")
    notes = root / "notes.txt"
    notes.write_text("hello\n", encoding="utf-8")
    fake = FakeOllama()
    fake.tokens = ["No change.\n", "```file\n", "x = 1\n", "```"]
    app, fake = _app(tmp_path, fake, file_service=service_for(root))
    with TestClient(app) as client:
        client.post(
            "/files/ai",
            headers=AUTH,
            json={"path": str(script), "instruction": "Leave it."},
        )
        client.post(
            "/files/ai",
            headers=AUTH,
            json={"path": str(notes), "instruction": "Leave it."},
        )
    assert fake.chat_bodies[0]["model"] == "qwen2.5:7b"
    assert fake.chat_bodies[1]["model"] == "qwen2.5:7b"
    assert script.read_text(encoding="utf-8") == "x = 1\n"


def test_ai_refuses_a_file_that_does_not_fit_the_context(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    script = root / "big.py"
    script.write_text("a" * 30_000, encoding="utf-8")
    fake = FakeOllama()
    fake.installed.append("qwen2.5-coder:14b")
    app, fake = _app(tmp_path, fake, file_service=service_for(root))
    with TestClient(app) as client:
        response = client.post(
            "/files/ai",
            headers=AUTH,
            json={"path": str(script), "instruction": "Shorten it."},
        )
    assert response.status_code == 413
    assert "by hand" in response.json()["detail"]
    assert fake.chat_bodies == []
    assert script.read_text(encoding="utf-8") == "a" * 30_000


def test_chat_can_attach_a_pc_file_without_changing_it(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    target = root / "context.txt"
    target.write_text("pump serial 14", encoding="utf-8")
    outside = tmp_path / "secret.txt"
    outside.write_text("nope", encoding="utf-8")
    app, fake = _app(tmp_path, file_service=service_for(root))
    with TestClient(app) as client:
        ok = client.post(
            "/chat",
            headers=AUTH,
            json={
                "messages": [{"role": "user", "content": "What serial is this?"}],
                "file": str(target),
            },
        )
        denied = client.post(
            "/chat",
            headers=AUTH,
            json={
                "messages": [{"role": "user", "content": "Read it"}],
                "file": str(outside),
            },
        )
    assert ok.status_code == 200
    attached = fake.chat_bodies[-1]["messages"][0]
    assert attached["role"] == "system"
    assert "pump serial 14" in attached["content"]
    assert fake.chat_bodies[-1]["messages"][1]["content"] == "What serial is this?"
    assert target.read_text(encoding="utf-8") == "pump serial 14"
    assert denied.status_code == 403


def test_extract_proposed_prefers_the_file_fence() -> None:
    reply = "Example ```python\nprint(1)\n```\nFull file:\n```file\nprint(1)\nprint(2)\n```"
    assert extract_proposed(reply) == "print(1)\nprint(2)\n"
    assert extract_proposed("no fence here") is None


def test_select_file_model_prefers_the_installed_coder_model(tmp_path: Path) -> None:
    from app.config import Settings

    settings = Settings(
        api_key="k",
        ollama_base_url="http://127.0.0.1:9",
        default_model="qwen2.5:7b",
        keep_alive=-1,
        host="127.0.0.1",
        port=8787,
        num_ctx=4096,
        temperature=0.7,
        state_path=tmp_path / "state.json",
        env_path=tmp_path / ".env",
        warm_on_start=False,
        coder_model="qwen2.5-coder:14b",
    )
    fake = FakeOllama()
    fake.installed = ["qwen2.5:7b", "qwen2.5-coder:14b"]

    import asyncio

    chosen = asyncio.run(select_file_model(fake, Path("main.py"), None, settings))
    plain = asyncio.run(select_file_model(fake, Path("notes.txt"), None, settings))
    override = asyncio.run(select_file_model(fake, Path("main.py"), "qwen2.5:7b", settings))
    fake.installed = ["qwen2.5:7b"]
    missing = asyncio.run(select_file_model(fake, Path("main.py"), None, settings))
    assert chosen == "qwen2.5-coder:14b"
    assert plain == "qwen2.5:7b"
    assert override == "qwen2.5:7b"
    assert missing == "qwen2.5:7b"


def test_upload_over_the_limit_on_the_api(tmp_path: Path) -> None:
    root = tmp_path / "drive"
    root.mkdir()
    files = service_for(root, max_upload_bytes=16)
    app, _fake = _app(tmp_path, file_service=files)
    with TestClient(app) as client:
        response = client.post(
            "/files/upload",
            headers=AUTH,
            data={"directory": str(root)},
            files={"file": ("big.txt", b"x" * 64, "text/plain")},
        )
    assert response.status_code == 413
    assert "MB" in response.json()["detail"]
    assert list(root.iterdir()) == []


async def _stream(payload: bytes):
    yield payload
