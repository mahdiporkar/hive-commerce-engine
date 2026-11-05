#!/usr/bin/env bash
# Builds the admin micro-frontends against the pinned Hive source (run tools/build-hive-starter.sh first).
set -euo pipefail
cd "$(dirname "$0")/../admin-mfes"
npm ci --no-audit --no-fund
npm test
