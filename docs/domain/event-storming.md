# Event storming (Phase 0, design-level)

**How this was produced:** a desk session by the engineering team (agent), working from the master prompt §6–§17 and the verified Hive constraints. **No domain experts took part.** Treat this as the input for a real workshop with the store owners (gemstone, aidastoor) before Phase 2. Hotspots marked ⚠ are open questions for that workshop.

Notation: **Actor** → `Command` → [Aggregate] → *Event* → (Policy / process) → next command. External systems appear in «guillemets».

## Flow 1 — Configure a store and its catalog

| Actor | Command | Aggregate | Event | Policy |
|---|---|---|---|---|
| Deployment | `BootstrapStore` (from config) | [Store] | *StoreBootstrapped* | create the default internal seller → `RegisterInternalSeller` |
| System | `RegisterInternalSeller` | [Seller] | *SellerActivated* | — |
| Catalog manager | `DefineAttribute` | [ProductType] (attribute definitions live with the product type that uses them) ⚠1 | *AttributeDefined* | — |
| Catalog manager | `PublishSchemaVersion` | [ProductType] | *ProductTypeSchemaPublished* | — |
| Catalog manager | `CreateCategory` / `AssignAttributes` | [Category] | *CategoryCreated* | — |
| Catalog manager / seller | `CreateProduct` | [Product] | *ProductCreated* | — |
| Catalog manager | `PublishListing` | [Listing] | *ProductPublished* | update search projection |

⚠1 The prompt lists `AttributeDefinition` separately. Attributes are reused across product types (for example `color`), so the model has a store-level **attribute library** (`AttributeDefinition` aggregate) and each `ProductType` schema version **copies** the definitions it uses at publish time. Editing the library never changes a published schema. → ADR-0015, change 1.

## Flow 2 — Seller onboarding and listing (MULTI_VENDOR / HYBRID)

| Actor | Command | Aggregate | Event | Policy |
|---|---|---|---|---|
| Participant | `ApplyAsSeller` | [Seller] | *SellerApplied* | notify moderators |
| Moderator | `VerifySeller` | [Seller] | *SellerVerified* | — |
| Moderator | `ApproveSeller` | [Seller] | *SellerApproved* | owner membership becomes active (same aggregate) |
| Seller owner | `InviteStaff` | [Seller] | *MembershipGranted* | — |
| Seller | `CreateOffer` | [SellerOffer] | *OfferCreated* | create stock → `OpenStockItem` |
| System | `OpenStockItem` | [StockItem] | *StockOpened* | — |
| Seller | `SubmitListingForApproval` | [Listing] | *ApprovalRequested* | — |
| Moderator | `ApproveListing` | [Listing] | *ListingApproved* → *ProductPublished* | search projection |
| Moderator | `SuspendSeller` | [Seller] | *SellerSuspended* | withdraw offers (eventual) → `WithdrawOffer` per offer |

⚠2 Who owns a product in a marketplace: the seller who created it, or the store? Assumption: the store owns the catalog entry, and `Product.createdBy` records the seller. Sellers may edit only products they created while those are unpublished. Other sellers attach offers to existing products.

## Flow 3 — Purchase (physical, any mode)

| Actor | Command | Aggregate | Event | Policy |
|---|---|---|---|---|
| Visitor | `CreateCart` / `AddItem` | [Cart] | *CartItemAdded* | — |
| Customer | `SignIn` «Hive» | — | — | merge guest cart → `MergeCart` |
| Customer | `StartCheckout` | (CheckoutProcess saga) | *CheckoutStarted* | `QuotePrices` (Pricing, sync) |
| CheckoutProcess | `Reserve` per line | [StockItem] | *StockReserved* / *ReservationRejected* | on rejection: release earlier lines, fail the checkout |
| CheckoutProcess | `PlaceOrder` | [Order] | *OrderPlaced* | split by seller inside the aggregate (`OrderSplitter`) |
| CheckoutProcess | `InitiatePayment` | [Payment] | *PaymentInitiated* | redirect the customer «PSP» |
| «PSP» webhook / customer return | `ConfirmPayment` (verified with the provider) | [Payment] | *PaymentCaptured* / *PaymentFailed* | — |
| Policy | `MarkOrderPaid` | [Order] | *OrderPaid* | consume reservations; start fulfillment; grant entitlements; post ledger |
| Policy (on *PaymentFailed* or TTL) | `CancelOrder` + `ReleaseReservation` | [Order], [StockItem] | *OrderCancelled*, *ReservationReleased* | — |
| Scheduler | `ExpireReservation` | [StockItem] | *ReservationExpired* | cancel the unpaid order |

⚠3 Reservation TTL vs. slow PSP confirmation: a payment captured after the reservation expired. Decision: `ConfirmPayment` still records the capture. If the stock is gone, the order goes to `PAID_UNFULFILLABLE` and an automatic refund starts. This is rare but must be handled, never ignored.

## Flow 4 — Fulfillment

Seller (or store staff) → `CreateShipment` → [Fulfillment] → *SellerOrderShipped* → (Ordering policy) → `MarkSellerOrderShipped` → [Order]. Then `ConfirmDelivery` → *SellerOrderDelivered* → starts the settlement hold period.

## Flow 5 — Digital purchase and delivery

| Actor | Command | Aggregate | Event | Policy |
|---|---|---|---|---|
| Seller | `RequestUpload` → «storage» → `FinalizeUpload` | [DigitalAsset] | *ContentVersionAdded* | — |
| Seller | `AttachAssetToProduct` | [DigitalAsset] | *AssetAttached* | — |
| Policy on *OrderPaid* (digital lines only) | `GrantEntitlement` | [Entitlement] | *EntitlementGranted* | — |
| Customer | `RequestDownload` | [Entitlement] | *DownloadGranted* | access log |
| Moderator / refund policy | `RevokeEntitlement` | [Entitlement] | *EntitlementRevoked* | — |

Hypothesis corrected (→ ADR-0015, change 4): the §6.1.3 map has Payment → Digital Delivery. Granting on *PaymentCaptured* and again on *OrderPaid* would create two triggers for one grant. **Digital Delivery listens only to *OrderPaid* / *OrderLineRefunded*.**

## Flow 6 — Refund

Customer service → `RequestRefund(lines, amount)` → [Payment] (refunds are entities inside Payment, so `Σ refunds ≤ captured` holds in one aggregate) → «PSP» → *RefundCompleted* → policies: Ordering marks lines refunded (*OrderLineRefunded*); Finance posts reversing journals; Digital Delivery revokes the entitlement if the license says so; Inventory restocks if the goods are returned (⚠4: only after a return is received, not at refund time).

## Flow 7 — Commission, settlement, payout

| Trigger | Command | Aggregate | Event |
|---|---|---|---|
| *OrderPaid* | `CalculateCommission` per seller order (`CommissionCalculator` + rules) | [CommissionRecord] | *CommissionCalculated* |
| *CommissionCalculated* | `PostJournal` (customer payable → seller payable + platform revenue) | [JournalEntry] | *JournalPosted* |
| *SellerOrderDelivered* + hold period | `ReleaseToAvailable` | [JournalEntry] | *JournalPosted* |
| Scheduler | `CloseSettlement` (per seller and period) | [Settlement] | *SettlementClosed* |
| Seller | `RequestPayout` | [Payout] | *PayoutRequested* |
| Finance | `ApprovePayout` | [Payout] | *PayoutApproved* → journal |
| *RefundCompleted* after a payout | `PostJournal` (reversal; seller balance may go negative and is netted in the next settlement) | [JournalEntry] | *JournalPosted* |

## Flow 8 — Mode upgrade (Scenario E)

Marketplace admin → `ChangeCommerceMode(MULTI_VENDOR)` → [Store] → *CommerceModeChanged* → policy: enable seller onboarding. Existing products, offers (all held by the internal seller) and orders are untouched, because the model was marketplace-shaped from the start. A downgrade is rejected while external sellers are active.

## Hypothesis review — summary

| §6.1 element | Verdict |
|---|---|
| Subdomain classification | Confirmed |
| 12 bounded contexts + Extensions | Confirmed, plus an explicit **Hive ACL** in a shared `platform` module (not a context) |
| `Customer` | Not a context in v1: customers are `CustomerId` (from the ACL's user id). Addresses are value objects in Checkout and Ordering. A Customer/Profile context can come later |
| Aggregates | Confirmed with changes 1–5 in ADR-0015 |
| Context map | Confirmed with change 4 (Digital Delivery only from Ordering) and change 5 (Finance cash-in from Payment, allocation from Ordering) |
