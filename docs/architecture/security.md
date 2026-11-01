# Security and authorization

Decisions: [ADR-0004](decisions/ADR-0004-participant-access-model.md), [ADR-0005](decisions/ADR-0005-identity-propagation-and-trust-boundary.md), [ADR-0007](decisions/ADR-0007-payments.md), [ADR-0008](decisions/ADR-0008-digital-file-delivery.md), [ADR-0009](decisions/ADR-0009-tenant-store-channel.md).

## Layer 1 — Hive (coarse, at the BFF)

- Operator operations (`/v1/admin/**`) are `AUTHENTICATED` with a declared `commerce.api.*:action`. Hive denies them before the engine is contacted.
- Participant operations (`/v1/shop/**`, `/v1/seller/**`) are `HYBRID`. Hive guarantees CSRF, an unforgeable identity and token forwarding for sessions, but makes no permission decision.
- Public catalog operations (`/v1/store/**`) are `PUBLIC`.

## Layer 2 — Commerce (data instance)

| Subject | Rule | Enforced in |
|---|---|---|
| Customer | owns cart, checkout, orders, entitlements where `customer.hive_user_id = UserContext.hiveUserId` | application service + repository predicate |
| Seller owner / staff | `seller_membership(active, capabilities)` for the seller that owns the offer, seller order, asset or balance | application service + repository predicate |
| Operator | Layer 1 action, plus `store_administrator` row for the deployment's store (bootstrapped from configuration for the first admin) | application service |
| Everyone | `tenant_id` + `store_id` from deployment config, never from the request; PostgreSQL RLS as defense in depth | repository + RLS |

Identifiers exposed to clients are random (UUIDv7 for rows, 128-bit random for asset ids and cart tokens). Not-found and not-yours return the same 404 to avoid existence leaks.

## Trust boundary

- The API port listens only on the private network shared with the BFF. The webhook port is separate and is the only one exposed through the ingress (`/hooks/payments/*`).
- JWTs are validated (issuer, audience, expiry, JWKS signature). `X-Hive-User-Id` is accepted only with a valid JWT and must match the recorded `(iss, sub)` binding.
- ProductionGuard (Hive starter) with Commerce rules refuses unsafe production configuration.
- No secrets in git. Secrets come from the environment or mounted files.

## Required security tests (Phase in brackets)

| Test | Phase |
|---|---|
| Forged `X-Hive-User-Id` directly to the service → 401; mismatched binding → 401 + audit | 1 |
| Product create denied by Hive without `commerce.api.catalog:create` (engine not contacted); allowed with grant | 1 |
| Anonymous call to every participant operation not marked anonymous-capable → 401 | 1 (registry-driven) |
| Customer cannot read another customer's order (404) | 2 |
| Return URL without provider verification does not mark the order paid | 2 |
| Replayed webhook changes nothing | 2 |
| Concurrent purchase of a quantity-1 item → exactly one order | 2 |
| Download URL denied for unpurchased or revoked content; expired URL fails at storage | 3 |
| Cross-store asset access with a guessed id fails | 3 |
| Seller A cannot read or modify Seller B's offers, orders or balances | 4 |
| Unauthorized users cannot approve sellers, listings or payouts | 4 |
| A refund cannot create a second payout | 4 |
