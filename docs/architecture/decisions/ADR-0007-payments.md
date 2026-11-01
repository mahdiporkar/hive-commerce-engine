# ADR-0007 — Payment confirmation, webhooks and return URLs (Gaps 3 and 4)

Status: proposed · Date: 2026-11-01

## Decision
- `PaymentProvider` port: `initiate(attempt) → redirect/instructions`, `verify(providerRef) → VerifiedResult`, `parseWebhook(headers, body) → WebhookEvent`. Signature verification happens inside the adapter.
- Each checkout attempt carries a unique idempotency key, and `(provider, provider_transaction_id)` is unique.
- **Return URL.** The browser lands on a storefront page, which calls `POST /commerce/v1/shop/payments/{attemptId}/verify` (HYBRID, with CSRF) through the BFF. Commerce then calls the provider's verify API. The redirect itself never changes state.
- **Webhooks.** A separate ingress, `https://<client-domain>/hooks/payments/{provider}`, is routed by the ingress directly to the engine's webhook port and **not** through the BFF. It accepts no cookies or sessions. It verifies signatures (or calls the provider back), stores each event in `payment_inbox` under a unique provider event id (so a replay is a no-op), and processes events idempotently.
- A `FakePaymentProvider` exists for development and test only, and ProductionGuard refuses it.

## Consequences
Both confirmation paths converge on one idempotent `confirmPayment` application service. Whichever arrives first wins, and the other becomes a no-op.
