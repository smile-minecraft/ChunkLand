#!/usr/bin/env bash
# Reproducible acceptance test for the chunkland-api dependency guard.
#
# The guard lives in chunkland-api/build.gradle.kts and fails the build when
# chunkland-api declares any external production dependency (SQLite, Bukkit/Paper,
# AceLib, or any other). This script proves the guard works without touching the
# real worktree: it operates on an isolated temp copy, injects a forbidden SQLite
# dependency, and asserts the build FAILS with a recognizable violation message.
#
# Usage: scripts/verify-api-dependency-guard.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== Copying project to isolated fixture: $WORK =="
rsync -a --exclude '/.git' --exclude '/.gradle' --exclude '**/build' "$ROOT/" "$WORK/"

echo "== Baseline: unmodified chunkland-api build must succeed =="
( cd "$WORK" && ./gradlew :chunkland-api:build --console=plain )

echo "== Injecting forbidden SQLite dependency into chunkland-api =="
printf '\ndependencies { implementation(libs.sqlite.jdbc) }\n' >> "$WORK/chunkland-api/build.gradle.kts"

echo "== Expecting build to FAIL due to the guard =="
set +e
( cd "$WORK" && ./gradlew :chunkland-api:build --console=plain ) > "$WORK/out.log" 2>&1
RESULT=$?
set -e

if [ "$RESULT" -eq 0 ]; then
  echo "FAIL: build succeeded despite forbidden dependency (guard missing or broken)"
  exit 1
fi

if ! grep -q "chunkland-api must not declare any external production dependency" "$WORK/out.log"; then
  echo "FAIL: build failed but the guard message was not found"
  cat "$WORK/out.log"
  exit 1
fi

if ! grep -q "org.xerial:sqlite-jdbc" "$WORK/out.log"; then
  echo "FAIL: build failed but the offending artifact was not identified"
  cat "$WORK/out.log"
  exit 1
fi

echo "PASS: build failed as expected (exit $RESULT) and the guard identified org.xerial:sqlite-jdbc"
