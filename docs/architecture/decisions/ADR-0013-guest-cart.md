# ADR-0013 — Guest cart

Status: proposed · Date: 2026-11-01

## Decision
`POST /v1/shop/carts` (HYBRID, anonymous allowed) returns a random 128-bit cart token. The storefront keeps it in its own first-party cookie, set by the Next.js server. Because the BFF forwards only allowlisted headers, the storefront sends the token as a query parameter (`?cart=`). Anonymous carts expire (configurable, default 7 days).

On the first signed-in cart call that carries a token, Commerce merges the guest cart into the customer's cart and invalidates the token. Quantities are capped by availability and unique items are deduplicated. Checkout requires a signed-in customer.

## Consequences
Cart tokens in URLs can leak through logs. Mitigations: a short TTL, rotation on login, and Hive's API logs, which store route templates without query strings (as stated in Hive's docs).
