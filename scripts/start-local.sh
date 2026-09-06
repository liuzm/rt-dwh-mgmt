#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
[[ -f deploy/.env ]] || { echo '请先根据 deploy/.env.example 配置 deploy/.env'; exit 1; }
mvn -f backend/pom.xml -DskipTests package
(cd frontend && npm run build)
docker compose --env-file deploy/.env -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml up -d --build
