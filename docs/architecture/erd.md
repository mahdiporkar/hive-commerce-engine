# ERD per bounded context

One PostgreSQL schema per context (ADR-0002); no cross-schema foreign keys (references are ids, shown as `ref`). Every table has `tenant_id`, `store_id`, `created_at`; mutable roots have `revision` and `updated_at`. Catalog and configuration rows are archived (`archived_at`), never deleted. Financial rows are immutable.

## store

```mermaid
erDiagram
  STORE ||--o{ SALES_CHANNEL : has
  STORE ||--o{ STORE_ADMINISTRATOR : "administered by (ref actor_id)"
  STORE {
      uuid id PK
      text key UK
      text mode
      char3 default_currency
      text_array locales
      jsonb capabilities
      bigint revision
  }
```

## catalog

```mermaid
erDiagram
  ATTRIBUTE_DEFINITION ||--o{ ATTRIBUTE_OPTION : offers
  PRODUCT_TYPE ||--|{ PRODUCT_TYPE_SCHEMA_VERSION : versions
  CATEGORY ||--o{ CATEGORY : parent
  CATEGORY ||--o{ CATEGORY_ATTRIBUTE : assigns
  PRODUCT ||--|{ PRODUCT_VARIANT : has
  PRODUCT ||--o{ PRODUCT_MEDIA : has
  PRODUCT_TYPE_SCHEMA_VERSION ||--o{ PRODUCT : validates
  PRODUCT_SEARCH_VALUE }o--|| PRODUCT : projects
  ATTRIBUTE_DEFINITION {
      uuid id PK
      text key
      text type
      jsonb rules
      jsonb flags
      jsonb labels
  }
  PRODUCT_TYPE_SCHEMA_VERSION {
      uuid id PK
      uuid product_type_id
      int version
      text status
      jsonb schema
      jsonb attribute_snapshot
  }
  PRODUCT {
      uuid id PK
      uuid schema_version_id
      uuid primary_category_id
      jsonb title
      jsonb attributes
      text kind
      ref created_by_seller
  }
  PRODUCT_VARIANT {
      uuid id PK
      uuid product_id
      text sku "UK per store"
      jsonb attributes
      jsonb variant_key "UK per product"
  }
```

## listing

```mermaid
erDiagram
  LISTING ||--o{ APPROVAL_REQUEST : moderated_by
  LISTING {
      uuid id PK
      ref product_id
      ref channel_id
      text status
  }
  SELLER_OFFER {
      uuid id PK
      ref seller_id
      ref variant_id
      numeric price
      char3 currency
      text condition
      bool unique_item
      text seller_sku
      text status
  }
```

`seller_offer`: unique `(store_id, seller_id, variant_id)` and `(store_id, seller_id, seller_sku)`.

## seller

```mermaid
erDiagram
  SELLER ||--|{ SELLER_MEMBERSHIP : staffed_by
  SELLER ||--o{ SELLER_VERIFICATION : evidence
  SELLER {
      uuid id PK
      text kind "INTERNAL|EXTERNAL"
      text status
      jsonb profile
  }
  SELLER_MEMBERSHIP {
      uuid id PK
      uuid seller_id
      ref actor_id
      text role
      text_array capabilities
      text status
  }
```

## pricing

```mermaid
erDiagram
  PROMOTION ||--o{ COUPON : activates
  PRICE_LIST ||--o{ PRICE_LIST_ENTRY : contains
```

## inventory

```mermaid
erDiagram
  STOCK_ITEM ||--o{ RESERVATION : holds
  STOCK_ITEM {
      uuid id PK
      ref offer_id UK
      int on_hand
      int reserved
      bool unique_item
  }
  RESERVATION {
      uuid id PK
      uuid stock_item_id
      ref checkout_attempt_id
      int quantity
      timestamptz expires_at
      text status
  }
```

Check constraints: `reserved >= 0`, `on_hand >= reserved`, `NOT unique_item OR on_hand <= 1`. `reserve` is `UPDATE ... SET reserved = reserved + :q WHERE id = :id AND on_hand - reserved >= :q` (row count 0 = rejected).

## checkout

```mermaid
erDiagram
  CART ||--o{ CART_ITEM : contains
  CHECKOUT_ATTEMPT }o--|| CART : from
  CART {
      uuid id PK
      ref customer_id "null for guest"
      bytea guest_token_hash UK
      char3 currency
      timestamptz expires_at
  }
  CHECKOUT_ATTEMPT {
      uuid id PK
      text idempotency_key UK
      text state
      jsonb quote
      ref order_id
      ref payment_id
  }
```

## ordering

```mermaid
erDiagram
  ORDER ||--|{ SELLER_ORDER : split_into
  SELLER_ORDER ||--|{ ORDER_LINE : contains
  ORDER {
      uuid id PK
      text number UK
      ref customer_id
      text status
      numeric total
      char3 currency
      jsonb addresses
  }
  SELLER_ORDER {
      uuid id PK
      uuid order_id
      ref seller_id
      text status
      numeric subtotal
  }
  ORDER_LINE {
      uuid id PK
      uuid seller_order_id
      ref offer_id
      text kind
      jsonb product_snapshot
      jsonb price_snapshot
      jsonb tax_snapshot
      int quantity
      numeric line_total
  }
```

## payment

```mermaid
erDiagram
  PAYMENT ||--o{ PAYMENT_TRANSACTION : records
  PAYMENT ||--o{ REFUND : refunds
  PAYMENT {
      uuid id PK
      ref order_id
      ref checkout_attempt_id UK
      numeric amount
      char3 currency
      text provider
      text status
  }
  PAYMENT_TRANSACTION {
      uuid id PK
      uuid payment_id
      text type
      text provider_tx_id
      text status
  }
  WEBHOOK_INBOX {
      uuid id PK
      text provider
      text provider_event_id
      jsonb body
      text status
  }
```

Unique: `payment_transaction(provider, provider_tx_id)`, `webhook_inbox(provider, provider_event_id)`.

## fulfillment

```mermaid
erDiagram
  FULFILLMENT ||--o{ SHIPMENT : ships
  FULFILLMENT {
      uuid id PK
      ref seller_order_id UK
      text status
  }
  SHIPMENT {
      uuid id PK
      uuid fulfillment_id
      text carrier
      text tracking
      text status
  }
```

## digital

```mermaid
erDiagram
  DIGITAL_ASSET ||--|{ CONTENT_VERSION : versions
  DIGITAL_ASSET ||--o{ PRODUCT_DIGITAL_ASSET : attached_to
  UPLOAD_SESSION }o--|| DIGITAL_ASSET : finalizes_into
  ENTITLEMENT ||--o{ DOWNLOAD_GRANT : issues
  DIGITAL_ASSET {
      text id PK "128-bit random"
      ref owner_seller_id
      text kind
  }
  CONTENT_VERSION {
      uuid id PK
      text asset_id
      int version
      text object_key
      text sha256
      bigint size
      text mime
      text scan_status
  }
  ENTITLEMENT {
      uuid id PK
      ref customer_id
      ref order_line_id UK
      ref product_id
      jsonb license_terms
      int downloads_used
      text status
  }
  DOWNLOAD_GRANT {
      uuid id PK
      uuid entitlement_id
      uuid content_version_id
      timestamptz expires_at
      inet requester
  }
```

## finance

```mermaid
erDiagram
  LEDGER_ACCOUNT ||--o{ JOURNAL_LINE : posted_to
  JOURNAL_ENTRY ||--|{ JOURNAL_LINE : balances
  COMMISSION_RULE ||--o{ COMMISSION_RECORD : applied_in
  SETTLEMENT ||--o| PAYOUT : paid_by
  JOURNAL_ENTRY {
      uuid id PK
      text type
      ref source_id
      text source_type
      ref reverses_entry_id
  }
  JOURNAL_LINE {
      uuid id PK
      uuid entry_id
      uuid account_id
      numeric debit
      numeric credit
      char3 currency
  }
  PAYOUT {
      uuid id PK
      uuid settlement_id UK
      ref seller_id
      numeric amount
      char3 currency
      text status
  }
```

`journal_entry` and `journal_line` are append-only (UPDATE/DELETE blocked by trigger). A deferred constraint trigger checks Σ debit = Σ credit per currency per entry at commit.

## platform (technical)

`outbox_event`, `processed_event(consumer, event_id)`, `identity_binding(issuer, subject → hive_user_id)`, `business_audit_event` (append-only), `extension_data`.
