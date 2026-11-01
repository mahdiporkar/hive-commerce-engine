# ADR-0006 — Hive resource vocabulary and route-operation conventions

Status: proposed · Date: 2026-11-01

## Decision
- One proxy route per client: key `commerce`, prefix `/commerce`, target `commerce-engine`, `FORWARD_TOKEN`, `stripPrefix=true`. Upstream API paths are versioned (`/v1/...`).
- Path namespaces encode the population (ADR-0004):
  - `/v1/store/**`: public catalog (`PUBLIC`)
  - `/v1/shop/**`: participant operations (`HYBRID`)
  - `/v1/seller/**`: seller workspace (`HYBRID`)
  - `/v1/admin/**`: operator operations (`AUTHENTICATED`)

  A test fails if any `/admin/` operation is not `AUTHENTICATED`.
- The API vocabulary lives in a resource-only module `commerce-api` (MODULE `commerce` with `API_RESOURCE` children). Admin MFEs are modules `commerce-admin-<area>`, each with a `PAGE` resource `commerce.admin.<area>` (action `view`).
- Manifests are templates in `hive/templates/`, rendered with `applicationKey` at client build time. Drafts for review live in `hive/drafts/`.
- Full table: [route-operations](../route-operations.md).

## Verification
Resolved in Phase 0: a resource-only module (`commerce-api`, no micro-frontend manifest) is accepted and published through the admin API, and AUTHENTICATED operations can reference its resources (`tests/smoke/phase0-bff-smoke.mjs`, run against Hive `a75871a`). `hive register` itself always registers an artifact per module, so client registration must call the admin API directly for this module (or use a small Commerce registration script) rather than plain `hive register`.
