#!/usr/bin/env bash
# Seeds categories, products and images from DummyJSON (FLS-011, ADR-0011).
# Needs the infra up (docker compose -f infra/docker/docker-compose.yml up -d --wait) and internet.
# Extra arguments go to Spring, e.g. --app.seed.dummyjson.page-size=50
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"

if [ -f "$root/infra/docker/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$root/infra/docker/.env"
  set +a
fi

cd "$root/backend"
exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,seed -Dspring-boot.run.arguments="$*"
