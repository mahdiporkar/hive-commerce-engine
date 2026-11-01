# ADR-0015 — DDD model: confirmed hypothesis and changes

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Context
Master prompt §6.1 gives a starting hypothesis for subdomains, bounded contexts, the context map and aggregates. It must be checked through event storming ([write-up](../../domain/event-storming.md); a desk session, with no domain experts yet).

## Decision
The hypothesis is adopted (subdomains, the 12 contexts plus Extensions, the aggregate list, rules and package layout), with these changes:

1. **Attribute library.** `AttributeDefinition` is its own store-level aggregate in Catalog. A published `ProductType` schema version copies the definitions it uses, so editing the library never alters a published schema.
2. **SKU scope.** `ProductVariant.sku` is unique per store (catalog identity). `SellerOffer.sellerSku` is optional and unique per seller. This replaces "SKUs are unique within the store" being enforced across sellers' own codes.
3. **UniqueItem** is not a separate aggregate. It is a `StockItem` with `unique = true` (`onHand ≤ 1`, never restocked above 1). The same reserve code path serves both kinds.
4. **Digital Delivery is triggered only by Ordering events** (`OrderPaid`, `OrderLineRefunded`), not by Payment events, so each grant or revocation has a single trigger.
5. **Finance consumes both**: Payment events for cash movement, Ordering events for allocation, commission and the hold release.
6. **Refund is an entity inside the Payment aggregate**, so `Σ refunds ≤ captured` is a single-aggregate invariant.
7. **No Customer context in v1.** `CustomerId` comes from the Hive ACL (Hive user id). Addresses are value objects owned by Checkout and copied into Ordering snapshots.
8. **Hive ACL** lives in a shared technical module `platform` (UserContext, identity binding, tenant check, Hive vocabulary constants). It is not a bounded context and holds no business rules.
9. **Reservation-expired-after-capture** is handled explicitly: the order goes to `PAID_UNFULFILLABLE` and an automatic refund starts.

## Consequences
- The aggregate catalogue, event catalogue and context map in `docs/domain/` are the reference; code reviews reject divergence unless a new ADR changes it.
- A workshop with the gemstone and aidastoor owners must review the ⚠ hotspots in the event-storming write-up before Phase 2 (product ownership in marketplaces, restock on returns, settlement hold periods).
