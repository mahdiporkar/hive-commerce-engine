# ADR-0013 — Guest cart

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Decision
`POST /v1/shop/carts` (HYBRID, anonymous allowed) returns a random 128-bit cart token. The storefront keeps it in its own first-party cookie, set by the Next.js server. Because the BFF forwards only allowlisted headers, the storefront sends the token as a query parameter (`?cart=`). Anonymous carts expire (configurable, default 7 days).

On the first signed-in cart call that carries a token, Commerce merges the guest cart into the customer's cart and invalidates the token. Quantities are capped by availability and unique items are deduplicated. Checkout requires a signed-in customer.

The engine never logs query strings (access log pattern and error logs record route templates only). A POST-body variant for cart reads is reconsidered in Phase 2.

## Consequences
Cart tokens in URLs can leak through logs. Mitigations: a short TTL, rotation on login, and Hive's API logs, which store route templates without query strings (as stated in Hive's docs).
