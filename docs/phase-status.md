# Phase status

## Phase 3 — Digital Commerce (COMPLETE)

Exit criterion: **Scenario B passes** (`DigitalDeliveryIntegrationTest`, real PostgreSQL and real S3-compatible storage): a store sells a PDF; after purchase the buyer gets a signed download; a non-buyer and an expired URL both fail.

Implemented (`commerce-digital-delivery`, schema `digital`):
- **Storage port** `FileStorage` with an S3-compatible adapter (MinIO client). Private bucket; server-side calls use the internal endpoint, signed URLs the public endpoint.
- **Upload:** a POST-policy form bound to key, content type and exact size; the browser posts straight to storage (never through the BFF). `finalize` checks size, SHA-256 and the content-sniffed MIME type, and runs the `MalwareScanner` port; only then is a version attached.
- **Assets:** `DigitalAsset` with immutable numbered `ContentVersion`s, unguessable ids, license terms (download limit, updates included, usage text), a public preview stored apart from paid content.
- **Entitlements:** created only by the `OrderPaid` consumer, unique per order line and asset. Ownership, revocation and limit are checked on every download. Buyers keep the purchased version, or receive later versions when the license includes updates.
- **Download:** JSON `{url, expiresAt}` with a TTL capped at 300 s, no 302 (ADR-0008). Small text (prompts) is returned inline after the same checks. Every grant is written to `download_access_log`.
- **Revocation:** by an operator, or automatically when a refund names the line (`RefundCompleted` → `OrderLineRefunded` → revoke).
- **Ordering:** digital-only seller orders are delivered when entitlements are granted (`DigitalDelivered`); `order_line_refund` records refunded lines without touching immutable lines.
- **Hive:** 9 new route operations (6 AUTHENTICATED, 2 HYBRID, 1 PUBLIC). Production guard rules for the storage endpoint and bucket creation.

Database migrations: `digital/V1__digital.sql`, `ordering/V2__order_line_refund.sql`.

Tests Executed:
```sh
./mvnw -B install -Dopenapi.write=true    # exit 0, all modules; commerce-app Tests run: 52, Failures: 0, Errors: 0
```

PASS:
- **Scenario B:** digital order without shipping; completes on entitlement grant; signed URL returns the exact bytes as an attachment; non-buyer gets an empty library and 404 with the entitlement id; URL expired after its TTL → 403 from storage; object without a signature → 403; license limit of 3 → fourth 409 `DOWNLOAD_LIMIT_REACHED`; 3 access-log rows.
- **Revocation:** refund naming the line → REVOKED → 409 `ENTITLEMENT_REVOKED`; direct operator revocation.
- **Upload verification:** wrong checksum, an executable declared as PDF, the EICAR test file, a disallowed type (422), and a file larger than the signed size (refused by storage itself) are all rejected.
- **Prompts:** text delivered inline only after purchase; public previews contain neither the text, object keys nor asset ids; update rights deliver version 2.
- **Mixed order:** physical plus digital: entitlement granted, one fulfillment opened, order stays PAID until shipped.
- **Isolation:** assets require a DIGITAL product; an entitlement row of another store is invisible to its own customer (404).

FAIL (and how fixed): the first test run failed because Docker Desktop had stopped between sessions; not a code problem. Restarted and re-run.

NOT EXECUTED (and why):
- A real malware scanner: the default adapter recognises only the EICAR test signature and records `NOT_SCANNED` otherwise. It is **not** protection. Production needs a real adapter (for example ClamAV) and `commerce.digital.require-scan=true`.
- Digital flow through the real Hive BFF (engine-level only; the BFF path is identical to the HYBRID operations proven in Phase 2).
- Seller-owned assets through the API (Phase 4).
- AWS S3 itself (only MinIO was run; the form URL assumes path-style addressing).

Known limitations:
- A signed URL can be shared for its lifetime and delivered content can be copied. No DRM is claimed.
- The download count is consumed when the URL is issued, so a failed fetch uses one download.
- `finalize` reads the whole object to hash it, so very large files take correspondingly long within the request.

Open Blockers: none.

Next phase: Phase 4 — Marketplace.

## Phase 2 — eShop Transaction Flow (COMPLETE)

Exit criterion: **Scenario A passes end to end through Hive** (`tests/e2e/commerce-through-bff.test.mjs`): two signed-in shoppers race for one unique item through the real BFF; exactly one order is placed (the other gets 409 `OUT_OF_STOCK`); payment is verified by a signed provider webhook; fulfillment is recorded and the order completes.

Implemented:
- **Listing / offers.** `SellerOffer` aggregate: price > 0 in the store currency, active seller, unique items at most 1, digital offers unlimited, seller SKU unique per seller. Operator offers for the internal seller. Public product detail now includes offers with live availability. Published `ListingQueries.offer` snapshot for checkout.
- **Inventory.** `StockItem` aggregate with TTL reservations closed exactly once (release, consume, expire); unique items capped at 1; unlimited digital stock; optimistic revision plus DB check constraints against overselling. Opened on `OfferCreated`, consumed on `OrderPaid`, released on `OrderCancelled`; expiry job emits `ReservationExpired`; operator stock adjustment.
- **Pricing.** `PriceCalculator` domain service: server-side prices, per-line tax rounded per currency, flat shipping per seller order with physical lines, a single currency per quote.
- **Checkout.**
  - Carts: guest carts identified by token hash, customer carts, merge at sign-in (ADR-0013).
  - `CheckoutProcess` saga on `CheckoutAttempt`: quote → reserve (all lines atomically) → place order → initiate payment. Each step is its own transaction with explicit compensation; idempotent per key; completes or compensates from Ordering events.
- **Ordering.** `Order` aggregate with seller orders (OrderSplitter), immutable line snapshots (no UPDATE grant on `order_line`), a total invariant, and explicit state machines. `PAID_UNFULFILLABLE` for a capture after the reservation expired. Customer order history (own orders only) and operator views.
- **Payment (ADR-0007).**
  - `PaymentProvider` port; `Payment` aggregate with idempotent capture, amount check, and refunds inside the aggregate (Σ ≤ captured, idempotent per key).
  - Customer verification endpoint (the redirect alone changes nothing).
  - Webhook ingress `/hooks/payments/{provider}`: signature check, inbox with replay protection, sessions and identity headers refused, not routable through the BFF.
  - Verification sweeper; automatic full refund for `PAID_UNFULFILLABLE`.
  - FAKE provider (development and tests only, refused by the ProductionGuard) with a simulated PSP page.
- **Fulfillment.** `Fulfillment` aggregate (PENDING → SHIPPED → DELIVERED) per seller order with physical lines; operator ship and deliver; events complete orders.
- **Hive.** 17 new route operations (8 AUTHENTICATED on `commerce.api.catalog:update` and `commerce.api.orders:view|fulfill|refund`; 9 HYBRID shop operations). Production guard rules for the fake provider and an HTTPS return URL.

Reused (from Hive / existing code): Hive CSRF and identity propagation for HYBRID operations; the Phase 1 outbox dispatcher, tenant transactions and anticorruption layer.

Database migrations: `listing/V2__seller_offer.sql`, `inventory/V1__inventory.sql`, `ordering/V1__ordering.sql`, `payment/V1__payment.sql`, `fulfillment/V1__fulfillment.sql`, `checkout/V1__checkout.sql`.

Tests Added: Maven suite now 127 tests: domain tests for stock, offers, pricing, orders, payments, fulfillment and carts, plus `CheckoutIntegrationTest` (9 scenarios). The e2e suite now has 10 subtests.

Tests Executed:
```sh
./mvnw -B install -Dopenapi.write=true                      # exit 0, Tests run: 127, Failures: 0, Errors: 0 (OpenAPI contract regenerated deliberately)
node --test tests/e2e/commerce-through-bff.test.mjs         # exit 0, 11 pass, 0 fail
```

PASS:
- **Scenario A (engine and through Hive):**
  - concurrent checkouts of a unique gem → one 201, one 409 `OUT_OF_STOCK`;
  - PSP approval plus browser return leaves the order PLACED;
  - signed webhook → PAID; a replayed webhook → "duplicate", no change; the webhook path is not routable through the BFF;
  - fulfillment ship and deliver → COMPLETED; availability 0; a retry by the losing buyer → `OUT_OF_STOCK`.
- **Payment and refunds:**
  - customer verification without a webhook → CAPTURED → PAID; idempotent checkout key returns the same attempt and order;
  - decline → order CANCELLED and stock released;
  - capture after reservation expiry → `PAID_UNFULFILLABLE` plus one completed automatic refund;
  - partial refund idempotent per key; over-refund → 409 `REFUND_EXCEEDS_CAPTURE`.
- **Isolation and access:**
  - another customer's order, payment and checkout → 404; order lists contain only own orders;
  - forged or session-carrying webhooks rejected (401/403);
  - guest cart created anonymously, priced, merged into the customer cart at sign-in, guest token invalidated; anonymous checkout → 401;
  - every customer-only HYBRID operation in the template answers 401 to anonymous callers (registry-driven, ADR-0004);
  - through Hive: an ungranted user is denied store orders before the engine; an anonymous HYBRID call reaches the engine and is refused by Commerce.

FAIL (and how fixed):
- **Architecture rule:** a webhook controller called the fake provider in infrastructure. Fixed by routing the call through the application service via a domain `Simulator` port.
- **Checkout imported other contexts' domain types:** fixed by publishing DTOs in `OrderingApi` and `PricingApi`.
- **Guest-cart merge reused line ids** (primary key conflict). Fixed: merge assigns new ids and empties the guest cart.
- **Product detail response shape changed** (it now includes offers); the test was updated.
- **The webhook test expected 403 for a bearer header,** but the resource server answers 401 first; both are refusals.

NOT EXECUTED (and why):
- Promotions, coupons and price lists (Pricing has tax and flat shipping only).
- Real PSP adapters (per client, Phase 5) and real tax/shipping carriers.
- Ledger postings (Phase 4, with commissions and settlements).
- Seller-side fulfillment (Phase 4).
- Hosted CI run.

Known limitations:
- A multi-line reservation is one Inventory transaction over several `StockItem` aggregates: a documented exception to one-aggregate-per-transaction, chosen for atomicity.
- Checkout steps call provider APIs outside database transactions; a crash between steps is recovered by retrying with the same idempotency key (the attempt resumes from its recorded state) or by reservation expiry.
- Text in carts and orders uses product snapshots at the time of the action.

Open Blockers: none.

Next phase: Phase 3 — digital commerce.

Commit SHA(s): the Phase 2 commit on `phase-1/foundation` (see `git log`).

## Phase 1 — Commerce Foundation (COMPLETE)

Exit criterion (master prompt §22): *a product can be created through the BFF by an authorized user and is denied for an unauthorized one*: **PASS**, through a real Hive BFF, authorization service, OpenFGA and Keycloak (`tests/e2e/commerce-through-bff.test.mjs`).

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
node --test tests/e2e/commerce-through-bff.test.mjs          # exit 0, 8 pass (run on commit 34dcd2b; see Phase 2 for the re-run)
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
