#!/usr/bin/env bash
# Assembles one engine version into a directory with the exact layout of a published release, so client repositories
# consume the same artifacts whether they come from the GitHub release or from this local build. Publishes nothing.
#   tools/package-release.sh <version> [out-dir]     (after ./mvnw -B clean install, tools/build-admin-mfes.sh)
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?usage: package-release.sh <version> [out-dir]}"
OUT="${2:-.local/release/$VERSION}"
rm -rf "$OUT"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

cp commerce-app/target/commerce-engine.jar "$OUT/commerce-engine.jar"
cp commerce-spi/target/commerce-spi-*.jar "$OUT/commerce-spi.jar"
cp docs/api/openapi.json "$OUT/openapi.json"
tar -czf "$OUT/admin-mfes.tar.gz" -C admin-mfes/dist modules
tar -czf "$OUT/hive-templates.tar.gz" hive/templates hive/register.mjs hive/HIVE_REVISION

(cd storefront-sdk && npm ci --no-audit --no-fund >/dev/null && npm run --silent build && npm pack --silent --pack-destination "$OUT" >/dev/null)
mv "$OUT"/hive-commerce-storefront-sdk-*.tgz "$OUT/storefront-sdk.tgz"
(cd builder && npm pack --silent --pack-destination "$OUT" >/dev/null)
mv "$OUT"/hive-commerce-builder-*.tgz "$OUT/builder.tgz"
(cd test-kit && npm pack --silent --pack-destination "$OUT" >/dev/null)
mv "$OUT"/hive-commerce-test-kit-*.tgz "$OUT/test-kit.tgz"

printf '%s\n' "$VERSION" > "$OUT/VERSION"
(cd "$OUT" && sha256sum commerce-engine.jar commerce-spi.jar openapi.json admin-mfes.tar.gz hive-templates.tar.gz storefront-sdk.tgz test-kit.tgz builder.tgz VERSION > SHA256SUMS)
echo "packaged $VERSION in $OUT"
