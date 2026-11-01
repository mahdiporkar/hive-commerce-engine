# Hive integration — verified capability matrix

Pinned Hive revision: **`a75871a64b9f6ab92c762a126e2fc94284a10429`** (`origin/platform-v1`, 2026-11-01, `0.1.0-dev.0`, Maven `0.1.0-SNAPSHOT`, Spring Boot `3.5.5`, Java 21, Node ≥ 22). Every fact below was checked against that tree, not against the master prompt's snapshot. Where they disagree, the "Correction" column says so.

## 1. Capabilities Commerce reuses

| Capability | Proof in Hive (file / test) | Status | Correction vs prompt |
|---|---|---|---|
| OIDC login, `HIVE_SESSION`, vault, CSRF | `services/bff/.../security/IdentityConfiguration.java`; `application.properties` (cookie `HttpOnly`, `Secure`, `SameSite=Lax`, **no `Domain`** → host-only); `npm run test:identity`, `test:keycloak` | Verified (code) | — |
| Runtime proxy, service targets, routes, operations | `services/bff/.../proxy/RuntimeProxy.java`; `services/authorization/.../routing/*`; `npm run test:routing` | Verified (code) | — |
| Identity propagation | `RuntimeProxy.java:126` adds `X-Hive-User-Id` + `X-Hive-Tenant-Id` **whenever a session exists**; `credential()` (l.158) injects the user token for `FORWARD_TOKEN` whenever a session exists — independent of `access` | Verified (code) | Important: **HYBRID operations also receive the user token** for signed-in callers (basis of ADR-0004) |
| Query strings forwarded | `docs/dynamic-routing.md` ("query passthrough"); used by University ERP (`ActingContextFilter`, `actingPosition` parameter) | Verified (doc + consumer precedent) | Not in prompt; lets the storefront pass a store/seller selector (always re-validated) |
| Coarse authorization at BFF | `/internal/routing/resolve` returns decision; deny before upstream contact | Verified (doc + test list) | — |
| Path pattern language | `routing/RoutePathPolicy.java:7` — segments are literal, `{param}` or `*` (plus trailing `/**`); no regex | Verified (code) | — |
| Resource catalog | `V3__authorization_catalog.sql`, `docs/resource-catalog.md`; key / action regexes as in prompt; `manage` reserved; ≤ 50 actions | Verified | — |
| Business roles / groups / grants | `access/AccessController.java`; `V3`: **`role_key` and `group_key` are globally `UNIQUE`** (not per application) | Verified (code) | Roles are install-global, not per application |
| Manifest governance | `V4__manifest_governance.sql`: **`module_key` globally `UNIQUE`, `^[a-z][a-z0-9-]{1,79}$` (no dots)** | Verified (code) | One module key cannot be registered under two applications in one install (Gap 8) |
| Route registry keys | `V5__dynamic_routing.sql`: `target_key`, `route_key`, **`path_prefix` globally `UNIQUE`** | Verified (code) | Two clients cannot both own `/commerce` in one install |
| MFE runtime + React adapter | `packages/react/src/index.tsx:20` `createReactMicroApp`; peer `react >=18.2 <20`; contract `1.1.0` | Verified (code) | — |
| Public context, SSR pattern | `docs/public-hybrid-authenticated.md`; `examples/react-tailwind-consumer/public-web` | Verified (doc) | Next.js itself is **NOT EXECUTED** in Hive's own evidence (phase-status) |
| Feature flags | `V6__feature_flags.sql` | Verified (schema present) | — |
| Extension points | `docs/extension-points.md`: HEADER, NAVIGATION, DASHBOARD, PROFILE, NOTIFICATIONS, LOGIN_EXPERIENCE, WORKSPACE_ACTIONS, RUNTIME_ACTIONS | Verified (doc) | — |
| Spring starter | `starters/hive-spring-boot-starter`: `CorrelationId`, `PlatformErrors`, `HiveException`, `Redaction`, `ProductionGuard` (rule list via `.rule(desc, supplier)`, `HIVE_PROFILE=production|development`) | Verified (code) | "Extend ProductionGuard" = construct one with Commerce rules; no subclassing needed |
| Starter distribution | parent `io.hiveplatform:hive-platform:0.1.0-SNAPSHOT` with `relativePath`; not published anywhere | Verified (code) | Consumers must install parent + starter from an exact commit (ERP does this in `backend/.local/hive-build`) |
| Request audit | API log + `route.invoked` audit | Verified (doc) | Not a business audit |
| Database version | `services/authorization/.../db/migration` contains **V1–V8** | Verified | `docs/versioning.md` still says V1–V7 (Hive doc drift, report only) |
| OpenAPI documents | phase-status "Deferred work" | **Not provided by Hive** | Commerce writes its own |

## 2. Capabilities Hive does not provide (confirmed)

File/object storage, notifications, server-side event bus, business audit/ledger, data-instance authorization, payment/tax/shipping, package registry — all confirmed absent. Additionally confirmed absent: **wildcard subjects** (`infra/openfga/model.fga` has no `user:*`), **default role on first login** and **IdP claim → group mapping** (`identity/IdentityRegistry.sync()` only inserts `hive_user` + `external_identity`), **application-scoped delegated administration** (phase-status "Deferred work").

## 3. Section 5 gaps — findings

| # | Gap | Finding | Decision |
|---|---|---|---|
| 1 | Customer default role | **Confirmed.** First login creates a role-less user; no wildcard subject, no claim mapping. Option (a) "grant to all users" is impossible; (b) impossible without a Hive change | ADR-0004 |
| 2 | Seller → Hive role | **Confirmed.** Only `SECURITY_ADMIN` can assign roles; delegated admin deferred | ADR-0004 |
| 3 | Webhooks vs CSRF | **Confirmed** (CSRF on every unsafe `/api/**` method, anonymous included) | ADR-0007 |
| 4 | Payment return URL | Confirmed as a design constraint (no Hive involvement) | ADR-0007 |
| 5 | Large files | **Confirmed.** `maxRequestBytes` 1 MiB / `maxResponseBytes` 5 MiB defaults; no redirect following, `Location` not relayed | ADR-0008 |
| 6 | Storefront domain / cookie | **Confirmed.** Cookie is host-only, `SameSite=Lax` → a gateway (shell or Next.js proxy to BFF) must be same-origin per storefront domain | ADR-0003 |
| 7 | Tenant vs Store | Tenant is fixed per identity provider (`identity_provider.tenant_id`); primary issuer → tenant `default` | ADR-0009 |
| 8 | Same module under two applications | **Confirmed blocker for a shared install**: module, route, target, prefix, role and group keys are install-global | ADR-0003 |
| 9 | CI coordination | Confirmed: `docs/caprover-ci.md` dispatches only to `university-erp` | ADR-0012 |

## 4. Local observation (not part of `platform-v1`)

The working copy at `C:\github\hive-platform` is on `fix/bff-identity-control-internal-http` (behind `platform-v1`) with **uncommitted** changes: `ScopedRuntimeController`/`ScopedRuntimeConfiguration` (a read-only `POST /runtime/scoped/check` for a named service client within one application and resource prefix) and a `cli/src/register.mjs` change (HYBRID module mode, `HIVE_TARGET_<KEY>_URL` override). Commerce does **not** depend on these. If they are merged, ADR-0005 may use the scoped check as an optional Layer-2 helper.

## 5. End-to-end smoke against a local Hive

See `docs/phase-status.md` → "Tests Executed" for the exact `hive up` / `hive doctor` / throwaway route results.
