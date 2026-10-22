#!/usr/bin/env bash
# One command to start Desk on Linux or macOS. Leave the window open.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT/server"

if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "Python 3.11 or newer is required. Install it, then run this again."
  exit 1
fi

"$PY" - <<'PY'
import sys
if sys.version_info < (3, 11):
    raise SystemExit("Python 3.11 or newer is required. This PC has " + sys.version.split()[0])
PY

if [[ ! -x .venv/bin/python ]]; then
  echo "Creating a Python environment..."
  "$PY" -m venv .venv
fi

echo "Installing the server. The first time takes a minute."
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m app.ensure_config
if [[ "${1:-}" == "--install-web-coder" ]]; then
  .venv/bin/python -m pip install -r requirements-web.txt
  .venv/bin/python -m app.setup_web_coder
fi
exec .venv/bin/python -m app.main

