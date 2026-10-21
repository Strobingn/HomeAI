import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.files import FileAccessError, FileService
from app.main import create_app
from app.workspace import WorkspaceStore, collect_context
from app import workspace_context
from tests.test_api import AUTH, FakeOllama, make_settings
from tests.test_workspace import reply


def test_long_path_preview_does_not_mean_incomplete_scan(tmp_path: Path):
    root = tmp_path / "project"
    folder = root / "android/app/src/main/java/com/example/project/ui"
    folder.mkdir(parents=True)
    for i in range(90):
        (folder / f"Screen{i:03}.kt").write_text(f"fun screen{i}() = {i}\n" + "// long source\n" * 200)
    snapshot = collect_context(FileService((str(root),)), str(root), "Explain the project", 10_000)
    assert snapshot.report["indexed"] == 90
    assert snapshot.report["scan_complete"] is True
    assert snapshot.report["truncated"] is False
    assert len(snapshot.report["tree"]) == 90
    assert len(snapshot.selected) >= 10
    assert snapshot.report["partial"]
    assert snapshot.report["context_chars"] <= 10_000


def test_overview_includes_source_not_tiny_build_and_test_files(tmp_path: Path):
    for name in ("README.md", "server/app/main.py", "android/app/src/main/java/MainActivity.kt", "android/gradlew", "server/tests/__init__.py", "server/tests/test_main.py"):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Project entry point\n" + "important source code\n" * (400 if "main" in name.lower() or name == "README.md" else 1))
    snapshot = collect_context(FileService((str(tmp_path),)), str(tmp_path), "Explain the project", 4000)
    assert "server/app/main.py" in snapshot.selected
    assert "android/app/src/main/java/MainActivity.kt" in snapshot.selected
    assert list(snapshot.selected).index("server/app/main.py") < list(snapshot.selected).index("android/gradlew")


def test_query_finds_lines_deep_inside_large_file_and_preserves_unseen_lines(tmp_path: Path):
    path = tmp_path / "screen.kt"
    before = "// untouched\n" * 500 + "fun showKeyboardBug() = oldBehavior()\n" + "// still untouched\n" * 500
    path.write_text(before)
    service = FileService((str(tmp_path),))
    snapshot = collect_context(service, str(tmp_path), "Fix showKeyboardBug in screen.kt", 2500)
    assert "screen.kt" in snapshot.excerpts
    fragment = snapshot.excerpts["screen.kt"][0]
    assert fragment["start_line"] > 450
    assert "showKeyboardBug" in fragment["content"]
    assert len(json.dumps(snapshot.prompt)) < len(before)
    store = WorkspaceStore()
    proposal = store.add(snapshot, reply([{"path": "screen.kt", "replacements": [{"old": "oldBehavior()", "new": "newBehavior()"}]}]))
    store.apply(proposal["id"], service)
    assert path.read_text() == before.replace("oldBehavior()", "newBehavior()")
    assert (tmp_path / "screen.kt.bak").read_text() == before
    with pytest.raises(FileAccessError):
        WorkspaceStore().add(snapshot, reply([{"path": "screen.kt", "content": "only an excerpt"}]))
    with pytest.raises(FileAccessError):
        WorkspaceStore().add(snapshot, reply([{"path": "screen.kt", "replacements": [{"old": "// still untouched\n" * 500, "new": ""}]}]))


def test_many_explicit_files_share_the_budget(tmp_path: Path):
    names = [f"source{i}.py" for i in range(9)]
    for name in names:
        (tmp_path / name).write_text("def entry():\n    return 'source'\n" * 400)
    snapshot = collect_context(FileService((str(tmp_path),)), str(tmp_path), "Update " + " ".join(names), 12_000)
    assert set(snapshot.selected) == set(names)
    assert snapshot.report["context_chars"] <= 12_000


def test_scan_and_read_limits_are_distinct_and_secrets_remain_excluded(tmp_path: Path, monkeypatch):
    (tmp_path / "a.py").write_text("code" * 100)
    (tmp_path / "b.py").write_text("other" * 100)
    (tmp_path / ".env").write_text("private key")
    (tmp_path / "credentials.json").write_text("private credentials")
    monkeypatch.setattr(workspace_context, "MAX_READ", 450)
    snapshot = collect_context(FileService((str(tmp_path),)), str(tmp_path), "Explain project", 4000)
    assert snapshot.report["indexed"] == 2
    assert snapshot.report["scan_complete"] is True
    assert snapshot.report["read_limited"] is True
    assert "private" not in json.dumps(snapshot.prompt)
    monkeypatch.setattr(workspace_context, "MAX_FILES", 1)
    snapshot = collect_context(FileService((str(tmp_path),)), str(tmp_path), "Explain", 4000)
    assert snapshot.report["scan_complete"] is False
    assert snapshot.report["truncated"] is True


def test_large_folder_stream_keeps_prompt_bounded_and_reports_the_whole_index(tmp_path: Path):
    folder = tmp_path / "ui"
    folder.mkdir()
    for i in range(90):
        (folder / f"Screen{i}.kt").write_text(f"fun screen{i}() = Unit\n" * 300)
    ollama = FakeOllama()
    app = create_app(make_settings(tmp_path), ollama, FileService((str(tmp_path),)))
    with TestClient(app) as client:
        response = client.post("/workspace/chat", headers=AUTH, json={"folder": str(tmp_path), "messages": [{"role": "user", "content": "Improve UI"}]})
    events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
    assert events[0]["context"]["indexed"] == 90
    assert events[0]["context"]["scan_complete"] is True
    assert len(events[0]["context"]["files"]) >= 10
    sent = ollama.chat_bodies[-1]
    assert sum(len(item["content"]) for item in sent["messages"]) < 18_000
    assert events[-1]["done"] is True
