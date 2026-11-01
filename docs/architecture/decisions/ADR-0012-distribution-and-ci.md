# ADR-0012 — Distribution, versioning and CI (Gap 9)

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Decision
- The engine is SemVer-versioned independently of Hive.
- Each release of repository A publishes:
  1. a container image `ghcr.io/mahdiporkar/hive-commerce-engine:<version>`, deployed by digest;
  2. Maven artifacts `commerce-spi` and `commerce-api-client` on GitHub Packages;
  3. admin MFE bundles with SRI, as release assets and as an npm package on GitHub Packages.
- B and C build their own image `FROM` the pinned engine image digest, adding their extension JARs and configuration. No submodules, no copied source.
- The Hive starter is built from the exact pinned commit (ADR-0001). The build copy's version is rewritten from `0.1.0-SNAPSHOT` to `0.1.0-<shortsha>` (currently `0.1.0-a75871a`) before `install`, so the installed artifact is unique per Hive commit and cannot collide with other local Hive builds (`tools/build-hive-starter.sh`). Only repository A depends on it; `commerce-spi` has no Hive dependency, so B and C never need the starter. Publishing the starter to GitHub Packages would need the Hive owner's approval.
- Commerce CI is separate from Hive's `release.yml`, and nothing is added to Hive's coordinator. Engine releases never auto-deploy to clients. B and C bump the pin through a PR that runs the engine contract tests (OpenAPI + SPI).
- Database migrations follow expand → migrate → contract, so version N-1 still runs against schema N during a rollback.
