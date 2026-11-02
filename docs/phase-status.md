# Phase status

## Phase 1 — Commerce Foundation (COMPLETE)

Exit criterion (master prompt §22): *a product can be created through the BFF by an authorized user and is denied for an unauthorized one*: **PASS**, through a real Hive BFF, authorization service, OpenFGA and Keycloak (`tests/e2e/catalog-through-bff.test.mjs`).

Implemented:
- Engine skeleton (ADR-0002): Maven modules `commerce-shared-kernel`, `commerce-platform`, `commerce-store`, `commerce-catalog`, `commerce-seller`, `commerce-listing`, `commerce-app`; Spring Boot 3.5.5, Java 21, Maven wrapper; ArchUnit boundary rules and a per-module SQL schema scan.
- Hive integration (ADR-0005, ADR-0016):
  - JWT resource server; `HiveIdentityFilter` anticorruption layer with identity binding and tenant check;
  - `@HiveOperation` enforcement for every handler;
  - Hive starter errors, correlation ids and `ProductionGuard` rules;
  - Hive templates for the resource manifest and the 16 route operations; registration verified against a real Hive.
- Tenant isolation (ADR-0009): forced RLS on every table; owner/runtime role split; transaction-local scope; no DELETE except on derived facet rows.
- Configuration (ADR-0014): startup validation against `config/schema/commerce-config-1.0.0.json` (JSON Schema 2020-12), listing every violation; capabilities `physicalProducts`, `digitalProducts`, `sellerOnboarding`, `commissions`.
- Store: deployment store with a deterministic id.
- Seller: the full Seller aggregate (lifecycle, owner/staff memberships with capabilities, last-owner rule, protected internal seller); the internal seller is bootstrapped in every mode; published `SellerDirectory` with the ADR-0016 relation check `allows(actor, seller, capability)`.
- Catalog:
  - attribute library (15 types);
  - product types with immutable versioned schemas and a product kind (PHYSICAL / DIGITAL);
  - category tree (materialized path, maximum depth, sibling-unique slugs, move with cycle and depth checks, attribute assignment, archive rules);
  - products and variants with a primary category;
  - published `CatalogQueries`.
- Listing: Listing aggregate (operator listings approved on creation; seller submissions moderated: approve, reject, resubmit; publish, unpublish); a public search projection with typed facet rows; public browsing with category subtree, text search, `f.<key>`, `min.<key>`/`max.<key>` filters, sorting, paging and facet counts; product detail by slug.
- Events: per-schema outboxes plus `OutboxDispatcher` (in-process, at least once, ordered per schema, `processed_event` deduplication per consumer, deployment-scoped).
- OpenAPI: `docs/api/openapi.json`, enforced by `OpenApiContractTest` (Scenario F groundwork).
- CI: `.github/workflows/ci.yml` (starter and services from the pinned commit, Maven suites, e2e suite).
- Scenario D: `fixtures/handicraft/catalog.json`, loaded through the API alone by a generic loader.

Reused (from Hive / existing code): the Hive starter (`CorrelationId`, `PlatformErrors`, `HiveException`, `Redaction`, `ProductionGuard`); Hive compose dependencies and jars from the pinned commit; Hive's harness pattern; University ERP's resource-server-plus-header pattern.

Database migrations: `platform/V1__identity_binding.sql`, `platform/V2__processed_event.sql`, `store/V1__store.sql`, `catalog/V1__catalog.sql`, `catalog/V2__category.sql`, `seller/V1__seller.sql`, `listing/V1__listing.sql`.

API / route-operation / manifest changes: 16 operations in `hive/templates/commerce-service.json`. That covers 13 AUTHENTICATED operator operations on `commerce.api.catalog` (`read`, `create`, `configure`, `publish`) and `commerce.api.moderation:decide`, plus 3 PUBLIC storefront operations. Resource manifest 0.1.0 (adds `read`).

Tests Added: 92 Maven tests (shared kernel 5, platform 2, store 3, catalog 46, seller 4, listing 2, app 30) plus 1 end-to-end test with 7 subtests.

Tests Executed:
```sh
tools/build-hive-starter.sh --services                       # exit 0
./mvnw -B install                                           # exit 0, Tests run: 92, Failures: 0, Errors: 0
node --test tests/e2e/catalog-through-bff.test.mjs          # exit 0, 8 pass (run on commit 34dcd2b; see Phase 2 for the re-run)
```

PASS:
- Everything listed for the first Phase 1 increment (domain, architecture, route contract, engine integration, through-Hive allowed/denied/CSRF/forgery/revocation).
- **Category rules:** depth, cycles, archive with children, slug and key validation; public tree; move and move-back; archive blocked with children.
- **Scenario D (handicraft):** 9 attributes, 1 product type, 6 categories and 3 products created and published through the API only; browsing by category subtree, enum filters, boolean plus `max` filter, measurement `min` filter, variant-level color facet, Persian text search and facet counts all return the expected products.
- **Listings:** publish → detail → unpublish (404) → republish (200).
- **Public search validation:** invalid keys, numbers and page sizes → 422; unknown category → empty result.
- **Platform:** the internal seller exists and is ACTIVE/INTERNAL; outbox events are delivered once (a repeated dispatch delivers nothing new); configuration schema accepts a valid configuration and reports 4 violations for an invalid one; OpenAPI contract matches.

FAIL (and how fixed):
- `MoneyTest`: the JDK's IRR minor units (2) contradict §12.11 (0). Fixed with an explicit minor-unit policy.
- Testcontainers 1.21.3 is rejected by Docker Engine 29. Fixed by pinning 1.21.4.
- e2e: assignment returns 200, not 201; the `/api/me/context` wait was replaced by probing the protected operation.

NOT EXECUTED (and why):
- Hosted CI run: the workflow is authored, but no hosted run has been observed. `hive-platform` access from GitHub Actions needs the optional `HIVE_PLATFORM_TOKEN` secret if the repository is private.
- Production-profile startup with an HTTPS issuer (development profile only).
- Admin micro-frontends: moved to Phase 5 (they need a running admin shell per client).

Known limitations:
- `/api/me/context` permission hints: Hive computes candidate permissions only for applications with active micro-frontend modules (`RuntimeContexts.userContext` at a75871a). A Commerce application with only the resource-only `commerce-api` module therefore shows no hints; once admin MFEs are active, hints cover every resource. Server-side decisions are unaffected.
- The identity binding trusts the first BFF-delivered pairing per external identity (ADR-0005).
- `JSON` attributes are bounded objects without schema-level validation of their content.
- Text search is a substring match on titles and slug (a search engine can replace it behind the projection port).

Open Blockers: none.

Next phase: Phase 2 — cart, checkout, orders, payments, inventory, fulfillment.

Commit SHA(s): `3e80136`, `34dcd2b`, and the Phase 1 completion commit on `phase-1/foundation`.

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
