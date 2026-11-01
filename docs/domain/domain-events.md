# Domain event catalogue

Conventions:
- Names are past tense. The type is `commerce.<context>.<EventName>` with an integer `version`.
- Envelope: `{eventId (UUIDv7), type, version, occurredAt, tenantId, storeId, aggregateType, aggregateId, aggregateRevision, correlationId, causationId, payload}`.
- Written to `outbox_event` in the same transaction as the aggregate change. Delivered at least once, so consumers dedupe on `eventId` (`processed_event` per consumer).
- Payloads carry ids and the facts consumers need. They never contain personal data beyond ids, secrets, object keys of paid content or prompt text.
- A breaking payload change adds a new version. Producers publish both versions during one minor release.

| Event | Producer | Consumers | Key payload |
|---|---|---|---|
| `StoreBootstrapped` v1 | Store | Seller (create internal seller) | storeId, mode, currency |
| `CommerceModeChanged` v1 | Store | Seller, Listing (enable onboarding / moderation) | from, to |
| `ProductTypeSchemaPublished` v1 | Catalog | Catalog search projection | productTypeId, schemaVersion |
| `ProductCreated` / `ProductUpdated` v1 | Catalog | Catalog search projection | productId, schemaVersion |
| `ProductPublished` / `ProductUnpublished` v1 | Listing | search projection, storefront cache | listingId, productId, channelId |
| `OfferCreated` v1 | Listing | Inventory (open StockItem) | offerId, sellerId, variantId, unique, initialQuantity |
| `OfferWithdrawn` v1 | Listing | Inventory, Checkout (invalidate cart lines) | offerId |
| `SellerApproved` v1 | Seller | Listing, notifications | sellerId |
| `SellerSuspended` v1 | Seller | Listing (withdraw offers), Finance (hold payouts) | sellerId, reason code |
| `StockReserved` v1 | Inventory | — (audit, metrics) | stockItemId, reservationId, quantity, expiresAt |
| `ReservationExpired` v1 | Inventory | Checkout (compensate), Ordering (cancel unpaid order) | reservationId, checkoutAttemptId |
| `OrderPlaced` v1 | Ordering | Payment (expected amount), notifications | orderId, customerId, total, sellerOrders[] |
| `PaymentCaptured` v1 | Payment | Ordering (mark paid), Finance (cash in) | paymentId, orderId, amount, provider, providerTxId |
| `PaymentFailed` v1 | Payment | Ordering (cancel), Checkout (compensate) | paymentId, orderId, reason code |
| `OrderPaid` v1 | Ordering | Inventory (consume), Fulfillment, Digital Delivery, Finance (allocate, commission) | orderId, lines[] (lineId, sellerOrderId, offerId, kind, quantity, amount) |
| `OrderCancelled` v1 | Ordering | Inventory (release), Finance (if paid: refund flow) | orderId, reason |
| `SellerOrderShipped` v1 | Fulfillment | Ordering, notifications | sellerOrderId, shipmentId |
| `SellerOrderDelivered` v1 | Fulfillment | Ordering, Finance (start hold) | sellerOrderId, deliveredAt |
| `RefundCompleted` v1 | Payment | Ordering, Finance (cash out) | refundId, paymentId, amount, lineIds[] |
| `OrderLineRefunded` v1 | Ordering | Digital Delivery (revoke per license), Finance (reverse allocation) | orderId, lineId, amount |
| `EntitlementGranted` / `EntitlementRevoked` v1 | Digital Delivery | notifications | entitlementId, customerId, productId |
| `CommissionCalculated` v1 | Finance | Finance (journal) | sellerOrderId, ruleId, rate, amount |
| `SettlementClosed` v1 | Finance | Finance (payout eligibility), notifications | settlementId, sellerId, period, balance |
| `PayoutApproved` v1 | Finance | Finance (journal), notifications | payoutId, sellerId, amount |
