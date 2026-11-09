#!/usr/bin/env bash
# Bootstrap a local RadioStar server venv, then run `setup`.
#
#   ./scripts/setup_server.sh             create .venv, install, create account
#   ./scripts/setup_server.sh --serve     ... and start the service
#   RADIOSTAR_VENV=/path/to/venv ./scripts/setup_server.sh
#
# Extra arguments are passed to `python -m server setup`.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
venv="${RADIOSTAR_VENV:-$root/.venv}"
python_bin="${PYTHON:-python3}"
start_server=0
setup_args=()

for arg in "$@"; do
    if [[ "$arg" == "--serve" ]]; then
        start_server=1
    else
        setup_args+=("$arg")
    fi
done

if [[ ! -x "$venv/bin/python" ]]; then
    printf 'Creating virtual environment: %s\n' "$venv"
    "$python_bin" -m venv "$venv"
fi

if ! "$venv/bin/python" -c 'import argon2, laion_clap, numpy, torch' >/dev/null 2>&1; then
    printf 'Installing CPU PyTorch (this can take a while)...\n'
    "$venv/bin/python" -m pip install --upgrade pip
    "$venv/bin/python" -m pip install \
        --index-url https://download.pytorch.org/whl/cpu \
        torch torchaudio torchvision
    "$venv/bin/python" -m pip install -r "$root/server/requirements.txt"
fi

"$venv/bin/python" -m server setup "${setup_args[@]}"

if [[ "$start_server" == 1 ]]; then
    exec "$venv/bin/python" -m server serve
fi

printf '\nStart the server with:\n  %s/bin/python -m server serve\n' "$venv"
