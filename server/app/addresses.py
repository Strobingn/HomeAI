"""Find the addresses the phone should use."""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
from pathlib import Path


def primary_ipv4() -> str | None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.settimeout(0.2)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()
    if ip.startswith("127."):
        return None
    return ip


def _tailscale_binary() -> str | None:
    found = shutil.which("tailscale")
    if found:
        return found
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidate = Path(local) / "Tailscale" / "tailscale.exe"
        if candidate.is_file():
            return str(candidate)
    return None


def tailscale_ipv4() -> str | None:
    binary = _tailscale_binary()
    if not binary:
        return None
    try:
        completed = subprocess.run(
            [binary, "ip", "-4"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in completed.stdout.splitlines():
        candidate = line.strip()
        if candidate.startswith("100."):
            return candidate
    return None


def banner_lines(host: str, port: int, env_path: Path, model: str) -> list[str]:
    lines = [
        "",
        "Desk server is listening.",
        "Leave this window open. Closing it stops the phone from connecting.",
    ]
    if host in {"127.0.0.1", "localhost"}:
        lines.append(
            f"HOST is {host}, so the phone cannot connect. Set HOST=0.0.0.0 in config/.env and start again."
        )
    ip = primary_ipv4()
    if ip:
        lines.append(f"Phone URL on this Wi-Fi:  http://{ip}:{port}")
    else:
        lines.append("This PC's Wi-Fi address was not detected.")
        lines.append("On Windows, run ipconfig. On Linux, run: hostname -I")
        lines.append(f"Then use http://THAT_ADDRESS:{port} in the phone.")
    tailscale = tailscale_ipv4()
    if tailscale:
        lines.append(f"Phone URL on Tailscale:   http://{tailscale}:{port}")
    else:
        lines.append("Tailscale is not running on this PC. At home, the Wi-Fi URL is enough.")
        lines.append(
            "Away from home, install Tailscale on this PC and the phone with the same login. Do not open router ports."
        )
    lines.append(f"API key file: {env_path}")
    lines.append(f"Warm model:   {model}")
    lines.append("")
    return lines
