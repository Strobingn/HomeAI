"""Publish verified, privately signed APKs and update manifests in CI."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def metadata(apk: Path, tools: Path) -> dict:
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package:
        raise ValueError("Could not read APK package metadata")
    signed = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})", signed)
    if len(certificates) != 1:
        raise ValueError("Expected one APK signing certificate")
    return dict(packageId=package[1], versionCode=int(package[2]), versionName=package[3],
                sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), size=apk.stat().st_size,
                certificateSha256=certificates[0].lower())


def main() -> None:
    if os.environ.get("GITHUB_REPOSITORY") != "Strobingn/HomeAI":
        raise SystemExit("Release publishing is configured only for Strobingn/HomeAI.")
    gh = shutil.which("gh")
    if not gh:
        raise SystemExit("GitHub CLI is required for release publishing.")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    sha = os.environ["GITHUB_SHA"]
    with tempfile.TemporaryDirectory(prefix="desk-updates-") as temporary:
        for channel, variant, label, package in (
            ("dev", "debug", "Desk-Dev", "com.homedesk.chat.dev"),
            ("stable", "release", "Desk", "com.homedesk.chat"),
        ):
            apk = Path(f"android/app/build/outputs/apk/{variant}/app-{variant}.apk")
            manifest = metadata(apk, tools)
            if manifest["packageId"] != package:
                raise ValueError("Wrong package for update channel")
            tag = f"desk-{channel}"
            name = f"{label}-{manifest['versionCode']}-{sha[:12]}.apk"
            copied = Path(temporary) / name
            shutil.copyfile(apk, copied)
            manifest["url"] = f"https://github.com/Strobingn/HomeAI/releases/download/{tag}/{name}"
            output = Path(temporary) / f"{channel}-update.json"
            output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            viewed = subprocess.run([gh, "release", "view", tag, "--repo", "Strobingn/HomeAI"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if viewed.returncode:
                args = [gh, "release", "create", tag, "--repo", "Strobingn/HomeAI", "--target", sha,
                        "--title", f"Desk {channel} updates", "--notes", "Signed APK updates. Use Settings → Check for app updates in Desk."]
                if channel == "dev":
                    args += ["--prerelease"]
                subprocess.run(args, check=True)
            # Publish an immutable APK name before replacing the channel manifest.
            subprocess.run([gh, "release", "upload", tag, str(copied), "--clobber", "--repo", "Strobingn/HomeAI"], check=True)
            subprocess.run([gh, "release", "upload", tag, str(output), "--clobber", "--repo", "Strobingn/HomeAI"], check=True)
            print(f"Published {channel} update {manifest['versionName']} ({manifest['versionCode']}).")


if __name__ == "__main__":
    main()
