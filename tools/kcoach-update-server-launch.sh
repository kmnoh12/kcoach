#!/bin/bash
set -euo pipefail

ROOT="/Users/kmnoh/Documents/Codex/2026-07-27/new-chat/work/kcoach"
cd "$ROOT"
export PYTHONUNBUFFERED=1
exec /usr/bin/python3 "$ROOT/tools/update_server.py" --host 0.0.0.0 --port 8765
