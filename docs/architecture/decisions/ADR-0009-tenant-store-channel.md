# ADR-0009 — Tenant, store and sales channel (Gap 7)

Status: proposed · Date: 2026-11-01

## Decision
- Commerce `tenant_id` = the Hive tenant from `X-Hive-Tenant-Id`: `default` for the primary issuer, the per-IdP tenant otherwise. For anonymous requests, the tenant comes from deployment configuration.
- `Store`, `SalesChannel`, `Seller` and `Customer` are Commerce concepts. v1 deploys one store per engine deployment (configured `store.key`), but the schema and every query still carry `tenant_id` + `store_id`.
- In v1, a request whose Hive tenant does not match the deployment's configured tenant is rejected with 403.
- PostgreSQL row-level security on `tenant_id`/`store_id` is added as defense in depth once the first repositories exist (Phase 1). It is set per transaction from `UserContext`.

## Consequences
A later shared multi-store deployment needs only store resolution from the request host, not a schema change.
