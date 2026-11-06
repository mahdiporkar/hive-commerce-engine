# ADR-0018 — Verified-purchase reviews live in Ordering

Status: accepted · Date: 2026-11-06

## Context
Aidastoor customers leave "reviews where allowed" (§16). The bounded contexts of ADR-0015 have no review context. The one rule
that matters is that only someone who paid for a product can review it, and Ordering is the only context that knows that.

## Decision
1. Reviews are a small aggregate in the Ordering context: one per customer and product, rating 1 to 5, a text body, and
   `VISIBLE` or `HIDDEN`. Moderation hides a review and never deletes it.
2. A store switches them on with `commerce.capabilities.reviews`, off by default. When off, reads return an empty result and
   writes are refused.
3. Public reads carry no customer identity. Writes are HYBRID at Hive and authenticated and decided by Commerce. A refunded line
   no longer counts as a purchase.
4. Public structured data does not include ratings until a store has decided how it presents them.

## Alternatives
- *A new Reviews context.* Cleaner on paper, but a whole module, schema and published API for a single rule. Reviews can move
  out if they gain replies, media or their own moderation workflow, because nothing else depends on their tables.
- *In Listing.* Rejected: Ordering already depends on Listing, so Listing cannot ask Ordering who paid without a cycle.
- *In each client.* Rejected: the verified-purchase rule would be duplicated, and clients hold no commerce logic.

## Consequences
- Ordering gains a responsibility beyond orders, recorded here so it can be revisited.
- Hard to reverse: low. One table with no foreign consumers.
