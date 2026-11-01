# Phase status

## Phase 1 — Commerce Foundation (PARTIAL: exit criterion met, scope items remaining)

Exit criterion (master prompt §22): *a product can be created through the BFF by an authorized user and is denied for an unauthorized one*: **PASS**, through a real Hive BFF, authorization service, OpenFGA and Keycloak.

Implemented:
- Maven multi-module engine (ADR-0002): `commerce-shared-kernel`, `commerce-platform`, `commerce-store`, `commerce-catalog`, `commerce-app`. Spring Boot 3.5.5, Java 21, Maven wrapper.
- Hive integration (ADR-0005, ADR-0016):
  - JWT resource server (issuer, audience `commerce-engine`, JWKS).
  - `HiveIdentityFilter`, the single anticorruption layer, produces `UserContext`. It rejects identity headers without a token, a missing identity, a foreign tenant, and a binding mismatch (with an audit row).
  - `@HiveOperation` route-operation enforcement for operator handlers, failing closed when undeclared.
  - Hive's `PlatformError` shape and correlation ids via the Hive starter; an access log without query strings; `CommerceProductionGuard` rules on top of Hive's `ProductionGuard`.
- Tenant isolation (ADR-0009):
  - Forced RLS on every tenant table from the first migration.
  - Flyway history per schema, run as the owner role.
  - The runtime role owns nothing, has no DELETE and cannot bypass RLS; `TenantTransactions` sets transaction-local scope.
- Store context: the deployment store comes from configuration with a deterministic id and is bootstrapped idempotently; mode-change invariant.
- Catalog context:
  - Attribute library with 15 validated attribute types.
  - Product types with versioned schemas; published versions are immutable in code and by DB trigger.
  - Products with variants: schema validation, unique combinations, store-unique SKUs, slugs.
  - Domain events written to a per-schema outbox.
- Operator API: define attribute, create product type, publish schema version, create product, read product.
- Hive templates: `hive/templates/commerce-api/resource-manifest.json` (adds action `read`) and `hive/templates/commerce-service.json` (target, route, 5 operations). `tools/build-hive-starter.sh` builds the starter (and, with `--services`, the Hive jars) from the pinned commit as `0.1.0-a75871a`.
- End-to-end harness `tests/e2e/`: Keycloak realm with an audience mapper, browserless OIDC login, Hive registration from the templates.

Reused (from Hive / existing code):
- Hive starter (`CorrelationId`, `PlatformErrors`, `HiveException`, `Redaction`, `ProductionGuard`).
- Hive compose dependencies and jars from the pinned commit; the harness pattern of Hive's `tests/support/stack.mjs`.
- University ERP's resource-server-plus-header pattern.

Changed files: `pom.xml`, `mvnw*`, `.mvn/`, `.gitattributes`, `.gitignore`, `commerce-*/**`, `deploy/sql/create-roles.sql`, `hive/templates/**`, `hive/drafts/gemstone/commerce-api/resource-manifest.json`, `tests/e2e/**`, `tools/build-hive-starter.sh`, and docs (`docs/testing/README.md`, this file, `route-operations.md`, `roadmap.md`, `README.md`).

Database migrations: `platform/V1__identity_binding.sql`, `store/V1__store.sql`, `catalog/V1__catalog.sql`.

API / route-operation / manifest changes:
- Operations `admin-attribute-define`, `admin-product-type-create` and `admin-product-type-publish` (`commerce.api.catalog:configure`), `admin-product-create` (`:create`), `admin-product-get` (`:read`).
- Resource manifest 0.1.0 gains action `read` on `commerce.api.catalog`. It was never published to a real installation, so there is no version bump.

Tests Added: 72 Maven tests (shared kernel 5, store 3, catalog 42, app 22 = architecture 9 + route contract 1 + engine integration 12), plus 1 end-to-end test with 7 subtests.

Tests Executed:
```sh
tools/build-hive-starter.sh --services                       # exit 0
./mvnw -B install                                           # exit 0, Tests run: 72, Failures: 0, Errors: 0
node --test tests/e2e/catalog-through-bff.test.mjs          # exit 0, 8 pass, 0 fail
node <hive-cli> validate hive/drafts/gemstone/commerce-api/resource-manifest.json   # PASS
```

PASS:
- **Domain:**
  - money (IRR 0 minor units, HALF_EVEN, no cross-currency arithmetic), localized text, store id and mode rules;
  - 31 attribute-type cases; product-type publish, version and variant rules;
  - product validation: all violations reported, unique combinations, single implicit variant, published-schema requirement, scope.
- **Build rules:** architecture rules, and the route contract (template ⇄ handlers; admin operations are AUTHENTICATED with declared actions).
- **Engine integration, allowed:** operator flow with an outbox event; 422 with violations; 409 `SKU_TAKEN` and `SCHEMA_VERSION_PUBLISHED`.
- **Engine integration, denied:**
  - anonymous 401;
  - forged header without a token 401 on admin and non-admin paths;
  - wrong audience, wrong issuer, expired, foreign-key and malformed tokens 401;
  - token without forwarded identity 401;
  - foreign tenant 403;
  - binding mismatch 401 plus a `security_event` row;
  - wrong or missing route operation 403.
- **Engine integration, isolation:** another store's product returns 404 by id; the runtime role without scope fails and cannot DELETE; the published-schema trigger blocks updates.
- **Through Hive, allowed:** the granted operator (Hive role `catalog-manager` → `commerce.api.catalog` read/create/configure) creates attributes, a product type and a product, and reads it back. The Hive identity is bound to the Keycloak subject and recorded as `created_by`.
- **Through Hive, denied:**
  - an ungranted signed-in user gets 403 `ACCESS_DENIED` on three operations; none of those correlation ids is in the engine log, and no rows are written;
  - an anonymous caller gets 401 without reaching the engine;
  - a missing CSRF header gets 403;
  - a forged `X-Hive-User-Id` sent directly to the engine gets 401;
  - after the role assignment is revoked, the BFF returns 403.

FAIL (and how fixed):
- `MoneyTest`: the JDK lists 2 minor units for IRR, but §12.11 requires 0. Fixed with an explicit minor-unit policy in `Money`.
- The engine integration tests could not start because Docker Engine 29 rejects Testcontainers 1.21.3. Fixed by pinning 1.21.4.
- End-to-end: role assignment returns 200, not 201 (assertion corrected). Waiting on `/api/me/context` permissions never succeeded, so the wait now probes the protected operation (see the limitations below).

NOT EXECUTED (and why):
- Phase 1 scope items not built yet: categories, listings, search projection and filtering, the default internal seller (Seller context), product update/archive and schema-migration jobs, the outbox dispatcher (events are written but not yet published), configuration JSON Schema validation (ADR-0014), the OpenAPI document, the GitHub Actions workflow, admin MFEs.
- Scenario D (handicraft configuration-only fixture): depends on categories.
- Production-profile startup with an HTTPS issuer: only the development profile was run. The production guard rules have no test yet; they have only been reviewed.

Known limitations:
- `/api/me/context` did not list the `commerce-api` permissions for the granted operator, although the server-side decisions were correct. Likely cause (unverified): the context includes only modules with an active micro-frontend artifact. This must be checked before admin MFEs rely on `context.permissions.can()`.
- The identity binding trusts the first BFF-delivered pairing per external identity (ADR-0005).
- `JSON` attributes are bounded objects; schema-level validation of their content is deferred.
- The end-to-end suite takes about 4 minutes (Keycloak and Hive core start-up) and is not yet in CI.
- The Phase 0 `hive up --build` retag of the local `hive/*:0.1.0-dev` images still applies.

Open Blockers: none.

Next phase: finish the Phase 1 scope items above (categories, listings, search projection, default seller, config schema, outbox dispatcher, OpenAPI, CI), then Phase 2 (cart, checkout, payments).

Commit SHA(s): `3e80136` (engine skeleton) and the follow-up commit with the end-to-end suite and docs, on `phase-1/foundation`.

## Phase 0 — Discovery and Architecture (COMPLETE, approved 2026-11-02)

Gate outcome: ADR-0004 approved by the owner with requirements R1–R5; all ADRs accepted after the [architecture review](architecture/adr-review.md); ADR-0016 added.

**ADRs as presented at the gate (now accepted):**
1. [ADR-0004](architecture/decisions/ADR-0004-participant-access-model.md): customers and sellers use `HYBRID` operations with Commerce-enforced identity and ownership; only operators are Hive-gated. **Needs an explicit owner decision** (the alternative is manual `SECURITY_ADMIN` role assignment per seller).
2. [ADR-0003](architecture/decisions/ADR-0003-one-hive-install-per-client.md): one Hive installation per client (forced by install-global key uniqueness).
3. [ADR-0015](architecture/decisions/ADR-0015-ddd-model.md): DDD model confirmed from the §6.1 hypothesis with 9 changes (attribute library, SKU scope, UniqueItem as StockItem flag, single trigger for entitlements, Finance inputs, Refund inside Payment, no Customer context in v1, Hive ACL module, capture-after-expiry).
4. [ADR-0002](architecture/decisions/ADR-0002-modular-monolith.md) (revised): one Maven module and one PostgreSQL schema per bounded context, JDBC not JPA, ArchUnit boundary rules.
5. [ADR-0012](architecture/decisions/ADR-0012-distribution-and-ci.md): GHCR image + GitHub Packages; Hive starter built from the pinned commit.
6. ADR-0001, 0005–0011, 0013, 0014: technical defaults.

Implemented:
- DDD model (master prompt v2 §6.1): [event storming](domain/event-storming.md) (desk session, 8 flows, hotspots for a stakeholder workshop), [glossary](domain/glossary.md) (English + proposed Persian), [context map](domain/context-map.md), [aggregate catalogue with invariants and the CheckoutProcess saga](domain/aggregates.md), [domain event catalogue](domain/domain-events.md), [domain model overview](architecture/domain-model.md), [ERD per context](architecture/erd.md), [extension system](architecture/extension-system.md).
- Repository discovery (below), the [verified Hive capability matrix](architecture/hive-integration.md), Section 5 gap findings, 14 ADRs, [overview with context map and sequence diagrams](architecture/overview.md), [security model](architecture/security.md), [route-operation table and resource vocabulary](architecture/route-operations.md), [ERD](architecture/erd.md), [repository boundaries](architecture/repository-boundaries.md), [roadmap and minimum vertical slice](roadmap.md), draft resource manifest `hive/drafts/gemstone/commerce-api/resource-manifest.json`, Hive pin `hive/HIVE_REVISION`, smoke test `tests/smoke/phase0-bff-smoke.mjs`.

Repository discovery (2026-11-01):

| Repo | Branch / SHA | State |
|---|---|---|
| hive-platform | `origin/platform-v1` @ `a75871a64b9f6ab92c762a126e2fc94284a10429` (`0.1.0-dev.0`, Java 21, Spring Boot 3.5.5, Maven, Node ≥ 22, TypeScript, Flyway V1–V8) | existing platform. The local working copy is on `fix/bff-identity-control-internal-http` (`ee7a1c6`) with **uncommitted** `ScopedRuntime*` and `cli/src/register.mjs` changes; left untouched, read from a clean export |
| hive-commerce-engine | remote: no refs (empty) | this local branch `phase-0/architecture` only, not pushed |
| gemstone-commerce | remote: no refs (empty) | untouched |
| aidastoor-commerce | remote: no refs (empty) | untouched |

Reused (from Hive / existing code):
- Hive BFF routing, CSRF, header allowlist, resource catalog and manifest governance, used unchanged.
- University ERP's patterns: JWT resource server + `X-Hive-User-Id` filter; exact-commit starter build; query-parameter context selectors.

Changed files: new files only (two commits: initial Phase 0, then the v2 DDD additions), in this repository (docs, `hive/`, `tests/smoke/`). No change to Hive, B or C.

Database migrations: none.

API / route-operation / manifest changes: draft vocabulary only (`commerce-api` module, 9 resources). Registered only in a throwaway local Hive, since destroyed.

Tests Added: `tests/smoke/phase0-bff-smoke.mjs` (+ `phase0-compose-override.yml`, a local echo upstream and HTTP target allowance).

Tests Executed:
```sh
# in a clean export of hive-platform@a75871a
npm ci && npm run build && ./mvnw -B -q package -DskipTests && npm run build:shell && npm run build:console   # exit 0
node cli/bin/hive.mjs validate hive/drafts/gemstone/commerce-api/resource-manifest.json                        # PASS
node cli/bin/hive.mjs up --build --profile ui --env-file <scratch>/hive.env --http-port 18180 --bff-port 18181 --authorization-port 18182 --project commerce-p0   # exit 0
node cli/bin/hive.mjs doctor --bff http://127.0.0.1:18181 --authorization http://127.0.0.1:18182 --origin http://127.0.0.1:18180   # exit 0
docker compose -p commerce-p0 -f infra/docker-compose/hive.yml -f tests/smoke/phase0-compose-override.yml ... up -d bff echo
node tests/smoke/phase0-bff-smoke.mjs hive/drafts/gemstone/commerce-api/resource-manifest.json                 # 10/10 PASS
node cli/bin/hive.mjs down --volumes --project commerce-p0
```

PASS:
- `hive validate`: draft resource manifest.
- `hive doctor`: BFF readiness, authorization readiness, compatibility of active modules, graph projection (pending 0, dead-lettered 0), route configuration.
- Smoke (10): resource-only module published; PUBLIC operation with a permission rejected (400); PUBLIC GET forwarded with stripped prefix and query; forged `Authorization`, `Cookie`, `X-Hive-User-Id` and custom headers not forwarded; `X-Correlation-Id`/`X-Hive-Route`/`X-Hive-Operation` forwarded; anonymous AUTHENTICATED → 401 `AUTHENTICATION_REQUIRED` without reaching upstream; unsafe call without CSRF → 403; HYBRID anonymous POST reaches upstream without identity or token; HYBRID POST without CSRF → 403; unregistered operation → 404.

FAIL (and how fixed): none.

NOT EXECUTED (and why):
- Signed-in flows: grant → AUTHENTICATED allowed, 403 without grant, and token + `X-Hive-User-Id` forwarding on HYBRID for a session. No IdP was configured in the throwaway stack. The code path is verified by reading `RuntimeProxy.java:126,158`. Phase 1 adds Keycloak to the test harness.
- Doctor checks for OpenFGA, PostgreSQL, Redis, IdP and manifest (flags not supplied; readiness covers DB and graph).
- Any engine code: none exists yet (Phase 1).
- Mermaid diagrams were not rendered by a tool; syntax checked by review only.

Known limitations:
- `hive up --build` retagged the local images `hive/authorization`, `hive/bff`, `hive/default-shell` and `hive/operator-console` at `:0.1.0-dev` to the `a75871a` build. Other local compose projects that use that tag (for example `haramyar`) get these images on their next recreate.
- `hive register` cannot register a resource-only module (it always registers an artifact); client registration will use the admin API for `commerce-api` (ADR-0006).
- Hive doc drift: `docs/versioning.md` says Flyway V1–V7, but V8 exists.

Open Blockers:
- Owner decision on ADR-0004 (and approval of the other proposed ADRs).
- Stakeholder event-storming workshop (gemstone and aidastoor owners) on the hotspots in docs/domain/event-storming.md before Phase 2.
- Persian glossary terms need review by a native-speaking domain expert.

Next phase: Phase 1 — Commerce Foundation (see [roadmap](roadmap.md)).

Commit SHA(s): `d02225a` and the following v2 commit on `phase-0/architecture` (pushed to origin).
