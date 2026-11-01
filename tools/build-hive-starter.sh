#!/usr/bin/env bash
# Builds io.hiveplatform:hive-spring-boot-starter from the pinned Hive commit (hive/HIVE_REVISION) and installs it
# into the local Maven repository as 0.1.0-<shortsha>, so it can never collide with another local Hive build (ADR-0012).
# With --services it also packages the BFF and authorization jars (used by the end-to-end suite).
# Source: a sibling hive-platform clone when present (read-only `git archive`), otherwise a fresh clone from GitHub.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHA="$(tr -d '[:space:]' < "$ROOT/hive/HIVE_REVISION")"
SHORT="${SHA:0:7}"
VERSION="0.1.0-$SHORT"
SRC="$ROOT/.local/hive-src/$SHORT"
SIBLING="${HIVE_PLATFORM_DIR:-$ROOT/../hive-platform}"

if [ ! -f "$SRC/pom.xml" ]; then
  rm -rf "$SRC"; mkdir -p "$SRC"
  if git -C "$SIBLING" cat-file -e "$SHA^{commit}" 2>/dev/null; then
    git -C "$SIBLING" archive "$SHA" | tar -x -C "$SRC"
  else
    TMP="$ROOT/.local/hive-clone"; rm -rf "$TMP"
    git clone --quiet --filter=blob:none --no-checkout https://github.com/mahdiporkar/hive-platform.git "$TMP"
    git -C "$TMP" archive "$SHA" | tar -x -C "$SRC"
    rm -rf "$TMP"
  fi
  # Rewrite only the build copy's coordinates; the Hive repository itself is never modified.
  find "$SRC" -name pom.xml -not -path "*/node_modules/*" -exec sed -i "s#<version>0.1.0-SNAPSHOT</version>#<version>$VERSION</version>#g" {} +
fi
cd "$SRC"
chmod +x mvnw
./mvnw -B -q -N install -DskipTests
./mvnw -B -q -f starters/hive-spring-boot-starter/pom.xml install -DskipTests
if [ "${1:-}" = "--services" ]; then
  ./mvnw -B -q package -DskipTests -pl services/authorization,services/bff -am
  echo "Packaged services/authorization and services/bff ($VERSION) in $SRC"
fi
echo "Installed io.hiveplatform:hive-spring-boot-starter:$VERSION from $SHA"
