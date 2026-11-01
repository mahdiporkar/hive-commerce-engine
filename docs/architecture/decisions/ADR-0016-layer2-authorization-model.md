# ADR-0016 — Layer-2 authorization model (OpenFGA-compatible)

Status: accepted · Date: 2026-11-02

## Context
ADR-0004 was approved with these requirements: Hive keeps authentication and platform-level authorization (R1); Commerce enforces seller, store, tenant, ownership and domain permissions on the backend (R2); authorization stays compatible with Hive's OpenFGA architecture (R3); seller onboarding needs no manual security administration (R4); security-sensitive operations have automated authorization and tenant-isolation tests (R5).

## Decision
1. **Layer 1 (Hive)** is unchanged: the BFF checks route-operation `access` and, for operators, `resourceKey:action` against Hive's OpenFGA graph. Commerce never writes to Hive's database or OpenFGA store and never calls Hive internals (R1, R3).
2. **Layer 2 (Commerce)** is a port in `commerce-platform`:
   ```java
   interface AccessPolicy { Decision check(Subject subject, Relation relation, ObjectRef object, TenantScope scope); }
   ```
   It uses OpenFGA-style relationships: `user:<hiveUserId>` is `owner` / `staff` of `seller:<id>`; `owner` of `order:<id>` and `entitlement:<id>`; `seller:<id>` is `holder` of `offer:<id>`. The v1 implementation evaluates them with SQL over Commerce's own tables (memberships, ownership columns), inside the same transaction as the use case. The relation names are fixed in a vocabulary file (`authz-model.md`, added in Phase 4) written as an OpenFGA model, so a later OpenFGA-backed implementation (a dedicated Commerce store, or a future Hive delegated-authorization feature) can replace the SQL one without changing callers (R3).
3. **Enforcement points** (R2): every application service that touches a participant- or seller-owned aggregate calls `AccessPolicy` before loading for update. Every repository query is additionally constrained by tenant, store and owner predicates, and RLS (ADR-0009) backs the tenant and store dimensions. Not-found and not-allowed both return 404 for participant-owned data; operators get 403.
4. **Operators** (Hive-authorized at Layer 1) administer the deployment's store in v1, because there is one store per deployment (ADR-0009). Layer 2 still applies tenant and store scope to every query. When multi-store deployments arrive, a `store:<id>#administrator` relation is added.
5. **Seller onboarding** (R4) creates and activates `SellerMembership` rows in Commerce. No Hive grant is involved.
6. **Tests** (R5): each relation has allowed and denied tests at the application level. Each participant endpoint has an anonymous → 401 test (registry-driven). Each operator endpoint has a through-BFF allowed/denied test. Tenant isolation is tested at the database level (RLS).

## Alternatives
- **Project Commerce data into Hive's OpenFGA store:** violates the Hive principle that Hive owns no business vocabulary, and creates a second writer to Hive's graph. Rejected.
- **A dedicated OpenFGA store for Commerce now:** a second source of truth that must be kept in sync with transactional data (an outbox to FGA for every membership and order). Deferred; the port keeps it possible.

## Consequences
One authorization model per layer, each with its own source of truth. Moving Layer 2 to OpenFGA later is a replacement behind a port, not a rewrite.
