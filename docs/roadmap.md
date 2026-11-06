# Roadmap

## Minimum vertical slice (Phases 1–2)

One store (`SINGLE_VENDOR`, IRR, fa/en), one product type (`generic-physical`: attributes `material` enum, `weight` measurement), and an implicit default seller. All steps go through a real local Hive (BFF + authorization + OpenFGA + PostgreSQL + Redis + Keycloak):

1. An operator with role `catalog-manager` creates the product type, a category and a product, then publishes it (`AUTHENTICATED` operations). A user without the grant gets 403 from the BFF and the engine logs no request.
2. An anonymous visitor lists and opens the product (`PUBLIC`).
3. The visitor creates a guest cart and adds the item (`HYBRID`, anonymous).
4. The visitor signs in through `/auth/login?returnUrl=/cart`, and the guest cart is merged.
5. Checkout reserves stock; payment is initiated with `FakePaymentProvider` (development profile).
6. The fake provider's webhook arrives at `/hooks/payments/fake`; the storefront return page calls `verify`; the order becomes `PAID` exactly once; a ledger journal is posted.
7. The customer sees the order in `GET /v1/shop/orders`; another customer gets 404 for it.

Exit criteria for Phase 1: step 1. For Phase 2: steps 2–7 plus Scenario A (two concurrent buyers of a quantity-1 item → one order).

## Phases

| Phase | Scope | Exit |
|---|---|---|
| 0 (complete) | Discovery, ADRs, vocabulary, slice | owner approval of this report |
| 1 (complete) | Engine skeleton (Boot 3.5.5, starter from pinned commit), ArchUnit, Flyway, UserContext/JWT, ProductionGuard rules, config schema, store/default seller, categories, product types + schema versions, products/variants/listings, search projection, Hive registration templates, local Hive + Keycloak test harness | slice step 1 through the BFF |
| 2 (complete) | Cart, checkout, reservations, orders, payment port + fake provider, webhook ingress + inbox, verification, fulfillment, order history, refunds (ledger moved to Phase 4) | Scenario A |
| 3 (complete) | Storage port + MinIO, upload/finalize, entitlements, signed downloads, prompt inline delivery | Scenario B |
| 4 (complete) | Seller onboarding (ADR-0004), memberships, offers, moderation, order splitting, commissions, settlements, payouts | Scenario C, E |
| 5 (complete except publishing) | B and C: Hive install, application registration, Next.js storefronts, taxonomy seeds, deployment | both run independently |
| 6 (complete) | Gemstone verification and PromptVerification extensions | outside core |
| 7 (complete) | Commerce Builder: new stores, schemas, capabilities, themes | a third store from configuration alone |

Handicraft Scenario D (configuration-only fixture) runs from Phase 1 onwards as a regression test. Scenario F (contract tests, upgrade and rollback) runs from each store repository; see [the release policy](deployment/release-policy.md).
