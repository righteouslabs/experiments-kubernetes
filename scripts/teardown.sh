#!/usr/bin/env bash
#
# Stops all containers and removes volumes.
#
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Stopping all services and removing volumes..."
docker compose down -v
echo "Done."
