import base64
import hashlib
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def test_update_manifest_reads_verified_apk_identity(tmp_path, monkeypatch):
    script = runpy.run_path(str(ROOT / "scripts/publish_updates.py"))
    apk = tmp_path / "signed.apk"
    apk.write_bytes(b"signed APK placeholder")
    calls = []
    def output(args, **kwargs):
        calls.append(args)
        if args[1] == "dump":
            return "package: name='com.homedesk.chat' versionCode='9' versionName='2.3.0' platformBuildVersionName='15'"
        return "Verifies\nSigner #1 certificate SHA-256 digest: " + "b" * 64
    monkeypatch.setattr(subprocess, "check_output", output)
    manifest = script["metadata"](apk, tmp_path)
    assert manifest["packageId"] == "com.homedesk.chat"
    assert manifest["versionCode"] == 9
    assert manifest["certificateSha256"] == "b" * 64
    assert manifest["sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
    assert calls[1][1:4] == ["verify", "--verbose", "--print-certs"]


def test_private_key_goes_via_stdin_not_args_or_console(tmp_path, monkeypatch, capsys):
    script = runpy.run_path(str(ROOT / "scripts/configure_updates.py"))
    store = tmp_path / "private.keystore"
    store.write_bytes(b"private-key-bytes")
    props = tmp_path / "signing.properties"
    props.write_text(f"STORE_FILE={store.as_posix()}\nSTORE_PASSWORD=secret-pass\nKEY_ALIAS=desk\nKEY_PASSWORD=secret-pass\n")
    monkeypatch.setenv("DESK_SIGNING_PROPERTIES", str(props))
    monkeypatch.setattr(script["shutil"], "which", lambda _: "gh")
    calls = []
    def run(args, **kwargs):
        calls.append((args, kwargs))
        return subprocess.CompletedProcess(args, 0)
    monkeypatch.setattr(subprocess, "run", run)
    script["main"]()
    secrets = [(args, kwargs) for args, kwargs in calls if args[1:3] == ["secret", "set"]]
    assert len(secrets) == 4
    assert next(kwargs["input"] for args, kwargs in secrets if args[3] == "DESK_KEYSTORE_BASE64") == base64.b64encode(store.read_bytes())
    assert all("secret-pass" not in " ".join(args) and "private-key-bytes" not in " ".join(args) for args, _ in calls)
    console = capsys.readouterr().out
    assert "secret-pass" not in console and "private-key-bytes" not in console
