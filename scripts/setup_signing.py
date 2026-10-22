"""Create a private, persistent Desk release identity outside the checkout.

Run with Python 3.11+ and a JDK (keytool) on PATH. Keep the entire output
directory backed up privately; losing it prevents in-place release updates.
"""
from __future__ import annotations

import os
from pathlib import Path
import secrets
import shutil
import subprocess


def main() -> None:
    tool = shutil.which("keytool")
    if not tool:
        raise SystemExit("Install JDK 17 and put keytool on PATH, then run this script again.")
    directory = Path.home() / ".homedesk"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    store = directory / "desk-release.keystore"
    properties = directory / "signing.properties"
    if store.exists() or properties.exists():
        raise SystemExit(f"Signing material already exists in {directory}. It was not replaced.")
    password = secrets.token_hex(24)
    env = dict(os.environ, DESK_NEW_STORE_PASSWORD=password)
    subprocess.run([
        tool, "-genkeypair", "-keystore", str(store), "-storetype", "PKCS12",
        "-storepass:env", "DESK_NEW_STORE_PASSWORD", "-keypass:env", "DESK_NEW_STORE_PASSWORD",
        "-alias", "desk", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=Desk Private Release", "-noprompt",
    ], env=env, check=True)
    properties.write_text(
        f"STORE_FILE={store.as_posix()}\nSTORE_PASSWORD={password}\nKEY_ALIAS=desk\nKEY_PASSWORD={password}\n",
        encoding="utf-8",
    )
    try:
        store.chmod(0o600)
        properties.chmod(0o600)
    except OSError:
        pass
    print(f"Private signing identity created in {directory}. Back up this directory privately.")
    print("Build: cd android, then gradlew.bat assembleRelease (Windows) or ./gradlew assembleRelease (Linux).")


if __name__ == "__main__":
    main()
