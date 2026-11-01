# ADR-0009 — Tenant, store and sales channel (Gap 7)

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Decision
- Commerce `tenant_id` = the Hive tenant from `X-Hive-Tenant-Id`: `default` for the primary issuer, the per-IdP tenant otherwise. For anonymous requests, the tenant comes from deployment configuration.
- `Store`, `SalesChannel`, `Seller` and `Customer` are Commerce concepts. v1 deploys one store per engine deployment (configured `store.key`), but the schema and every query still carry `tenant_id` + `store_id`.
- In v1, a request whose Hive tenant does not match the deployment's configured tenant is rejected with 403.
- PostgreSQL row-level security is enabled **from Phase 1** on every tenant table: `ENABLE` and `FORCE ROW LEVEL SECURITY`, policy `tenant_id = current_setting('app.tenant_id') AND store_id = current_setting('app.store_id')`. The runtime role owns no tables; Flyway migrates as a separate owner role. Scope is set with `set_config(..., true)` (transaction-local) at the start of every transaction; when it is missing, `current_setting` raises an error and the query fails closed. Background jobs set scope explicitly per store.

## Consequences
A later shared multi-store deployment needs only store resolution from the request host, not a schema change.
