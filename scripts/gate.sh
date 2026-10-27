#!/usr/bin/env bash
# Deterministic gate for /ship: compile, test, format/style. Exit 0 = PASS.
set -u

cd "$(dirname "$0")/../backend" || { echo "GATE FAIL [setup]: backend/ not found"; exit 1; }

if [ ! -f pom.xml ]; then
  echo "GATE FAIL [setup]: backend/pom.xml not found — backend is not scaffolded yet"
  exit 1
fi

if [ -x ./mvnw ]; then MVN=./mvnw; else MVN=mvn; fi

run_step() {
  local name="$1"; shift
  echo "== gate: $name"
  if ! "$MVN" -q -B "$@"; then
    echo "GATE FAIL [$name]"
    exit 1
  fi
}

run_step compile compile
run_step test test
run_step style spotless:check checkstyle:check

echo "GATE PASS"
