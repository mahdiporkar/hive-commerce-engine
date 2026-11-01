# ADR-0007 — Payment confirmation, webhooks and return URLs (Gaps 3 and 4)

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Decision
- `PaymentProvider` port: `initiate(attempt) → redirect/instructions`, `verify(providerRef) → VerifiedResult`, `parseWebhook(headers, body) → WebhookEvent`. Signature verification happens inside the adapter.
- Each checkout attempt carries a unique idempotency key, and `(provider, provider_transaction_id)` is unique.
- **Return URL.** The browser lands on a storefront page, which calls `POST /commerce/v1/shop/payments/{attemptId}/verify` (HYBRID, with CSRF) through the BFF. Commerce then calls the provider's verify API. The redirect itself never changes state.
- **Webhooks.** A separate ingress, `https://<client-domain>/hooks/payments/{provider}`, is routed by the ingress directly to the engine's webhook port and **not** through the BFF. It accepts no cookies or sessions. It verifies signatures (or calls the provider back), stores each event in `payment_inbox` under a unique provider event id (so a replay is a no-op), and processes events idempotently.
- A `FakePaymentProvider` exists for development and test only, and ProductionGuard refuses it.
- **Safety nets:** a verification sweeper re-verifies attempts stuck in `PAYMENT_INITIATED` (for PSPs that require an explicit verify, or whose webhooks are missing); a daily reconciliation job compares captured and refunded amounts with PSP reports and opens cases on mismatch; refund requests carry their own idempotency key; a capture that arrives after the reservation expired moves the order to `PAID_UNFULFILLABLE` and starts an automatic refund (ADR-0015).

## Consequences
Both confirmation paths converge on one idempotent `confirmPayment` application service. Whichever arrives first wins, and the other becomes a no-op.
