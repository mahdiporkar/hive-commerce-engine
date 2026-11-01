# ADR-0011 — Money and the double-entry ledger

Status: proposed · Date: 2026-11-01

## Decision
- `Money(amount: BigDecimal, currency: ISO-4217)`. Scale and rounding come from a currency table (IRR = 0 minor units; HALF_EVEN unless configured). Adding amounts in different currencies throws. Toman is a display formatter only.
- `ledger_entry` is append-only: a DB trigger blocks UPDATE and DELETE. It is double-entry: each journal must balance per currency, checked in the same transaction. There are accounts per store, seller, platform and customer payable. Balances are derived, and a reconciliation job compares derived balances with cached ones.
- Refunds post reversing journals. A payout consumes settled seller balance under a unique `(payout_id)` constraint, so a refund cannot create a second payout.
