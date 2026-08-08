#!/usr/bin/env bash
# Regenerates every screenshot in the README, against the real stack.
#
# Two things make this more than "open a browser and press Print Screen".
#
# The browser runs in a container on the compose network, because the dashboard bakes its API
# origin in at build time and a containerised browser cannot reach the localhost that origin
# normally points at. So the stack is brought up with docker-compose.codespaces.yml and
# OPENPAY_PUBLIC_URL=http://edge:8000 — service names the browser can actually resolve. Shell
# variables beat platform/docker/.env in compose, so this leaves that file alone.
#
# And the acquirer is stopped for the duration. The failover screenshot is the only image here
# that cannot be staged: the attempt rows say what they say because a bank really was down when
# that payment went through. A system that fails over correctly looks exactly like one that never
# had to, so the only way to photograph the difference is to cause it.
set -euo pipefail

cd "$(dirname "$0")/.."

VICTIM="${ACQUIRER:-mock-bank-a}"
IMAGE="mcr.microsoft.com/playwright:v1.49.0-noble"
OUT_DIR="docs/images"

COMPOSE=(docker compose
    -f platform/docker/docker-compose.yml
    -f platform/docker/docker-compose.apps.yml
    -f platform/docker/docker-compose.codespaces.yml
    --profile shop)

export OPENPAY_PUBLIC_URL=http://edge:8000
export STOREFRONT_GATEWAY_PUBLIC_URL=http://edge:8000
export OPENPAY_DASHBOARD_ORIGINS=http://dashboard:8080

if [ ! -f platform/docker/.env ]; then
    echo "No platform/docker/.env — run ./scripts/demo.sh first." >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

echo "Bringing the stack up with container-resolvable origins..."
"${COMPOSE[@]}" up -d --build

# pwd -W gives the Windows-style path Docker Desktop needs for a bind mount; elsewhere plain pwd
# is already right. MSYS_NO_PATHCONV stops Git Bash rewriting the container-side paths.
HOST_PATH=$(pwd -W 2>/dev/null || pwd)

restore() {
    echo "Restoring $VICTIM..."
    "${COMPOSE[@]}" start "$VICTIM" >/dev/null 2>&1 || true
}

echo "Stopping $VICTIM so the failover shot is real..."
"${COMPOSE[@]}" stop "$VICTIM" >/dev/null 2>&1
trap restore EXIT INT TERM

# The image ships the browsers but not the npm package, so playwright is installed into /tmp and
# the browser download is skipped — they are already at /ms-playwright.
MSYS_NO_PATHCONV=1 docker run --rm \
    --network docker_default \
    -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    -v "$HOST_PATH/scripts:/work:ro" \
    -v "$HOST_PATH/$OUT_DIR:/out" \
    "$IMAGE" \
    sh -c 'cd /tmp \
        && npm init -y >/dev/null 2>&1 \
        && npm i playwright@1.49.0 --no-audit --no-fund >/dev/null 2>&1 \
        && cp /work/capture-screenshots.mjs /tmp/ \
        && node /tmp/capture-screenshots.mjs'

echo
echo "Screenshots written to $OUT_DIR."
