#!/usr/bin/env bash
#
# Brings up the full demo stack using Docker Compose.
# Waits for infrastructure to become healthy before the app services start
# (handled by healthchecks in docker-compose.yml).
#
set -euo pipefail
cd "$(dirname "$0")/.."

echo "========================================="
echo " Multi-Version Microservice Demo"
echo "========================================="
echo ""
echo "Starting infrastructure + services..."
echo ""

docker compose up --build -d

echo ""
echo "Tailing logs (Ctrl+C to stop watching, services keep running)..."
echo ""
docker compose logs -f
