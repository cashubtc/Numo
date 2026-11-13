#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p integration-tests/arkoor/logs
docker compose -f integration-tests/arkoor/docker-compose.yml logs --no-color \
    > integration-tests/arkoor/logs/services.log 2>&1
docker compose -f integration-tests/arkoor/docker-compose.yml ps --all \
    > integration-tests/arkoor/logs/status.txt 2>&1
docker compose -f integration-tests/arkoor/docker-compose.yml cp \
    mint:/data/mint/logs integration-tests/arkoor/logs/mint || true
