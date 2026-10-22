"""Upload private APK signing secrets with GitHub CLI; never print the key."""
from __future__ import annotations

import base64
import os
from pathlib import Path
import shutil
import subprocess


def main() -> None:
    gh = shutil.which("gh")
    if not gh:
        raise SystemExit("Install GitHub CLI from https://cli.github.com/ and run gh auth login, then retry.")
    properties = Path(os.environ.get("DESK_SIGNING_PROPERTIES", str(Path.home() / ".homedesk/signing.properties"))).expanduser()
    if not properties.is_file():
        raise SystemExit("Run python scripts/setup_signing.py first to create a private signing identity.")
    values = {}
    for line in properties.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    required = {"STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}
    if not all(values.get(key) for key in required):
        raise SystemExit("The private signing properties are incomplete. No secrets were uploaded.")
    store = Path(values["STORE_FILE"]).expanduser().resolve()
    checkout = Path(__file__).resolve().parent.parent
    if store.is_relative_to(checkout):
        raise SystemExit("Use a private keystore outside the checkout, created by setup_signing.py.")
    secrets = {"DESK_KEYSTORE_BASE64": base64.b64encode(store.read_bytes()).decode("ascii")}
    secrets.update({f"DESK_{key}": values[key] for key in required - {"STORE_FILE"}})
    for args in (["auth", "status"], ["repo", "view", "Strobingn/HomeAI", "--json", "nameWithOwner"]):
        result = subprocess.run([gh, *args], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if result.returncode:
            raise SystemExit("GitHub CLI cannot access the repository. Run gh auth login with an account allowed to manage its Actions secrets.")
    for name, value in secrets.items():
        result = subprocess.run([gh, "secret", "set", name, "--repo", "Strobingn/HomeAI"],
                                input=value.encode("utf-8"), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if result.returncode:
            raise SystemExit(f"Could not configure {name}. Check GitHub CLI permissions and retry; existing private material was preserved.")
    print("Private signing configured. Back up ~/.homedesk privately; never commit it.")
    print("Run Build Desk APK on your branch in GitHub Actions to publish signed Desk and Desk Dev updates.")
    print("Older APKs signed with another key require a one-time reinstall. Preserve needed chats before uninstalling.")


if __name__ == "__main__":
    main()
