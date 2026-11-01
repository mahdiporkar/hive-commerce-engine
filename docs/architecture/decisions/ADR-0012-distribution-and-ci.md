# ADR-0012 — Distribution, versioning and CI (Gap 9)

Status: proposed · Date: 2026-11-01

## Decision
- The engine is SemVer-versioned independently of Hive.
- Each release of repository A publishes:
  1. a container image `ghcr.io/mahdiporkar/hive-commerce-engine:<version>`, deployed by digest;
  2. Maven artifacts `commerce-spi` and `commerce-api-client` on GitHub Packages;
  3. admin MFE bundles with SRI, as release assets and as an npm package on GitHub Packages.
- B and C build their own image `FROM` the pinned engine image digest, adding their extension JARs and configuration. No submodules, no copied source.
- In CI, the Hive starter is built from the exact pinned commit (ADR-0001) into an isolated Maven repository, as University ERP does. Publishing the starter to GitHub Packages would need the Hive owner's approval.
- Commerce CI is separate from Hive's `release.yml`, and nothing is added to Hive's coordinator. Engine releases never auto-deploy to clients. B and C bump the pin through a PR that runs the engine contract tests (OpenAPI + SPI).
- Database migrations follow expand → migrate → contract, so version N-1 still runs against schema N during a rollback.
