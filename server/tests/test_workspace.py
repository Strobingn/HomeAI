from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.files import FileAccessError, FileService
from app.workspace import Snapshot, WorkspaceStore, collect_context
from tests.test_api import AUTH, FakeOllama, make_settings
from app.main import create_app


def reply(edits: list[dict]) -> str:
    return "Proposed changes:\n```desk-changes\n" + json.dumps(edits) + "\n```"


def project(tmp_path: Path):
    root = tmp_path / "project"
    root.mkdir()
    (root / "a.py").write_text("print('old')\n", encoding="utf-8")
    (root / "b.txt").write_text("old b\n", encoding="utf-8")
    service = FileService((str(root),))
    snapshot = collect_context(service, str(root), "a.py b.txt", 10000)
    return root, service, snapshot


def test_scan_uses_subfolders_and_excludes_secrets_builds_and_shortcuts(tmp_path: Path):
    root, service, _ = project(tmp_path)
    (root / "src").mkdir()
    (root / "src" / "work.py").write_text("important")
    (root / ".env").write_text("SECRET=password")
    (root / "private.key").write_text("key")
    (root / "node_modules").mkdir()
    (root / "node_modules" / "dependency.js").write_text("excluded")
    (root / "ignored.txt").write_text("exclude me")
    (root / ".deskignore").write_text("ignored.txt\n")
    external = tmp_path / "outside.txt"
    external.write_text("secret")
    try:
        (root / "shortcut.txt").symlink_to(external)
    except OSError:
        pass
    snap = collect_context(service, str(root), "work", 10000)
    assert "src/work.py" in snap.selected
    assert not ({".env", "private.key", "ignored.txt", "shortcut.txt"} & snap.selected.keys())
    assert all("node_modules" not in name for name in snap.selected)


def test_context_limit_reports_omissions_and_prioritizes_named_files(tmp_path: Path):
    root, service, _ = project(tmp_path)
    (root / "large.py").write_text("x" * 2000)
    snap = collect_context(service, str(root), "b.txt", 60)
    assert list(snap.selected) == ["b.txt"]
    assert snap.report["omitted"] == 2


@pytest.mark.parametrize("path", ["../outside.py", "/tmp/no.py", "C:/no.py", "a/../no.py", "a\\no.py", ".env", "secrets.json"])
def test_proposal_refuses_unsafe_or_sensitive_paths(tmp_path: Path, path: str):
    _, _, snap = project(tmp_path)
    with pytest.raises(FileAccessError):
        WorkspaceStore().add(snap, reply([{"path": path, "content": "bad"}]))


def test_proposal_refuses_edits_to_omitted_files(tmp_path: Path):
    root, _, snap = project(tmp_path)
    (root / "unseen.txt").write_text("unseen")
    with pytest.raises(FileAccessError) as exc:
        WorkspaceStore().add(snap, reply([{"path": "unseen.txt", "content": "bad"}]))
    assert exc.value.status == 409


def test_apply_saves_multiple_files_creates_nested_file_and_is_idempotent(tmp_path: Path):
    root, service, snap = project(tmp_path)
    store = WorkspaceStore()
    proposal = store.add(snap, reply([
        {"path": "a.py", "content": "print('new')\n"},
        {"path": "b.txt", "content": "new b\n"},
        {"path": "docs/new.md", "content": "# New\n"},
    ]))
    assert proposal
    assert (root / "b.txt").read_text() == "old b\n"
    result = store.apply(proposal["id"], service)
    assert len(result["saved"]) == 3
    assert (root / "b.txt").read_text() == "new b\n"
    assert (root / "b.txt.bak").read_text() == "old b\n"
    assert (root / "docs/new.md").read_text() == "# New\n"
    assert store.apply(proposal["id"], service) == result
    assert len(list(root.glob("*.bak"))) == 2


def test_conflict_preflight_writes_nothing(tmp_path: Path):
    root, service, snap = project(tmp_path)
    store = WorkspaceStore()
    proposal = store.add(snap, reply([{"path": "a.py", "content": "new a"}, {"path": "b.txt", "content": "new b"}]))
    (root / "b.txt").write_text("someone else's change")
    with pytest.raises(FileAccessError) as exc:
        store.apply(proposal["id"], service)
    assert exc.value.status == 409
    assert (root / "a.py").read_text() == "print('old')\n"
    assert not list(root.glob("*.bak"))


def test_write_failure_restores_previous_writes(tmp_path: Path, monkeypatch):
    root, service, snap = project(tmp_path)
    store = WorkspaceStore()
    proposal = store.add(snap, reply([{"path": "a.py", "content": "new a"}, {"path": "b.txt", "content": "new b"}]))
    original = service.write_text
    def fail_second(path, content):
        if path.endswith("b.txt"):
            raise OSError("disk failed")
        return original(path, content)
    monkeypatch.setattr(service, "write_text", fail_second)
    with pytest.raises(FileAccessError):
        store.apply(proposal["id"], service)
    assert (root / "a.py").read_text() == "print('old')\n"
    assert (root / "a.py.bak").is_file()


def test_replaced_folder_symlink_is_refused_at_save(tmp_path: Path):
    root, service, snap = project(tmp_path)
    outside = tmp_path / "other"
    outside.mkdir()
    store = WorkspaceStore()
    proposal = store.add(snap, reply([{"path": "docs/new.txt", "content": "no"}]))
    try:
        (root / "docs").symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("symlinks unavailable")
    with pytest.raises(FileAccessError):
        store.apply(proposal["id"], service)
    assert not (outside / "new.txt").exists()


def test_folder_chat_streams_context_proposal_then_separate_save(tmp_path: Path):
    root, service, _ = project(tmp_path)
    ollama = FakeOllama()
    ollama.tokens = [reply([{"path": "a.py", "content": "changed\n"}])]
    app = create_app(make_settings(tmp_path), ollama, service)
    with TestClient(app) as client:
        body = {"folder": str(root), "messages": [{"role": "user", "content": "Change a.py"}]}
        assert client.post("/workspace/chat", json=body).status_code == 401
        response = client.post("/workspace/chat", headers=AUTH, json=body)
        assert response.status_code == 200
        events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
        assert "a.py" in events[0]["context"]["files"]
        proposal = events[-1]["proposal"]
        assert (root / "a.py").read_text() == "print('old')\n"
        assert client.post("/workspace/apply", json={"proposal_id": proposal["id"]}).status_code == 401
        saved = client.post("/workspace/apply", headers=AUTH, json={"proposal_id": proposal["id"]})
        assert saved.status_code == 200
        assert (root / "a.py").read_text() == "changed\n"
        assert client.post("/workspace/apply", headers=AUTH, json={"proposal_id": "unknown"}).status_code == 410
        assert ollama.chat_bodies[-1]["options"]["num_ctx"] >= 8192


def test_health_checks_are_concurrent_and_have_overall_deadline(tmp_path: Path):
    class ConcurrentOllama(FakeOllama):
        def __init__(self):
            super().__init__()
            self.started = set()
            self.all_started = asyncio.Event()
        async def probe(self, name):
            self.started.add(name)
            if len(self.started) == 3:
                self.all_started.set()
            await asyncio.wait_for(self.all_started.wait(), 0.5)
        async def version(self):
            await self.probe("version")
            return await super().version()
        async def tags(self):
            await self.probe("tags")
            return await super().tags()
        async def running_names(self):
            await self.probe("running")
            return await super().running_names()
    with TestClient(create_app(make_settings(tmp_path), ConcurrentOllama())) as client:
        response = client.get("/health", headers=AUTH)
        assert response.status_code == 200
        assert response.json()["status"] == "ok"


def test_exact_replacements_preserve_unchanged_file_contents(tmp_path: Path):
    root, service, snap = project(tmp_path)
    store = WorkspaceStore()
    proposal = store.add(snap, reply([
        {"path": "a.py", "replacements": [{"old": "'old'", "new": "'new'"}]},
        {"path": "b.txt", "replacements": [{"old": "old b", "new": "new b"}]},
    ]))
    store.apply(proposal["id"], service)
    assert (root / "a.py").read_text() == "print('new')\n"
    assert (root / "b.txt").read_text() == "new b\n"


def test_ambiguous_or_missing_replacement_cannot_be_saved(tmp_path: Path):
    root, _, snap = project(tmp_path)
    (root / "repeat.txt").write_text("repeat repeat")
    snap.selected["repeat.txt"] = "repeat repeat"
    for old in ["repeat", "missing", ""]:
        with pytest.raises(FileAccessError):
            WorkspaceStore().add(snap, reply([
                {"path": "repeat.txt", "replacements": [{"old": old, "new": "bad"}]},
            ]))
    assert (root / "repeat.txt").read_text() == "repeat repeat"
