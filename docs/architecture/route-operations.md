# Route operations and resource vocabulary

Conventions: [ADR-0004](decisions/ADR-0004-participant-access-model.md) (who is gated where) and [ADR-0006](decisions/ADR-0006-hive-vocabulary-and-route-conventions.md) (naming). Every key below satisfies Hive's syntax: resource `[a-z][a-z0-9._-]{0,159}`, action `[a-z][a-z0-9_-]{0,79}`, module `[a-z][a-z0-9-]{1,79}`. `manage` is never declared.

## Hive objects per client installation

| Object | Key | Notes |
|---|---|---|
| Application | `<client>` (`gemstone`, `aidastoor`) | owned by the client repository |
| Service target | `commerce-engine` | `baseUrl` = internal engine URL; `maxRequestBytes` 1 MiB (default) |
| Proxy route | `commerce` → prefix `/commerce` | `FORWARD_TOKEN`, `stripPrefix=true` |
| Module (resources only) | `commerce-api` | API vocabulary below |
| Modules (MFEs) | `commerce-admin-catalog`, `commerce-admin-orders`, `commerce-admin-sellers`, `commerce-admin-finance`, `commerce-admin-settings`, `commerce-admin-digital` | one `PAGE` resource each |

## Resource tree (module `commerce-api`)

```text
<client>                         APPLICATION (root, created with the application)
  commerce                       MODULE        actions: view
    commerce.api.catalog         API_RESOURCE  actions: read, create, update, publish, archive, configure
    commerce.api.stores          API_RESOURCE  actions: view, configure
    commerce.api.orders          API_RESOURCE  actions: view, fulfill, cancel, refund
    commerce.api.sellers         API_RESOURCE  actions: view, approve, suspend
    commerce.api.moderation      API_RESOURCE  actions: review, decide
    commerce.api.finance         API_RESOURCE  actions: view, settle, approve_payout
    commerce.api.digital         API_RESOURCE  actions: view, revoke
    commerce.api.customers       API_RESOURCE  actions: view
```

Admin MFE modules each add `commerce.admin.<area>` (`PAGE`, action `view`) attached to the application root, because a module manifest may attach only to the root or to its own nodes.

## Suggested operator roles (created by the client, granted by a SECURITY_ADMIN)

| Role | Grants |
|---|---|
| `marketplace-admin` | `manage` on `commerce` (implies all actions below it) |
| `catalog-manager` | catalog: read, create, update, publish, archive, configure; moderation: review, decide |
| `order-manager` | orders: view, fulfill, cancel; customers: view |
| `finance` | finance: view, settle, approve_payout; orders: refund |

## Operations — minimum vertical slice (Phase 1–2)

The source of truth for implemented operations is [`hive/templates/commerce-service.json`](../../hive/templates/commerce-service.json); `RouteContractTest` fails the build if it and the engine handlers disagree. Rows marked ✔ are implemented.

Browser path = `/api/routes/commerce` + upstream path. Path segments: literal, `{param}` or `*` (Hive `RoutePathPolicy`).

| Method | Upstream path | Access | resource:action | Purpose |
|---|---|---|---|---|
| GET | `/v1/store/products` | PUBLIC | — | list published listings (filters as query) |
| GET | `/v1/store/products/{slug}` | PUBLIC | — | product detail |
| GET | `/v1/store/categories` | PUBLIC | — | category tree |
| POST | `/v1/shop/carts` | HYBRID | — | create cart (guest or customer) |
| GET | `/v1/shop/carts/current` | HYBRID | — | read cart (`?cart=` for guests) |
| POST | `/v1/shop/carts/current/items` | HYBRID | — | add item |
| DELETE | `/v1/shop/carts/current/items/{itemId}` | HYBRID | — | remove item |
| POST | `/v1/shop/checkouts` | HYBRID ✱ | — | start checkout (reserves stock) |
| POST | `/v1/shop/checkouts/{checkoutId}/payments` | HYBRID ✱ | — | initiate payment (idempotency key in body) |
| POST | `/v1/shop/payments/{attemptId}/verify` | HYBRID ✱ | — | server-side verification after return URL |
| GET | `/v1/shop/orders` | HYBRID ✱ | — | my orders |
| GET | `/v1/shop/orders/{orderId}` | HYBRID ✱ | — | my order (ownership checked) |
| GET | `/v1/shop/me` | HYBRID ✱ | — | memberships and context for storefront/MFEs |
| POST | `/v1/admin/attributes` | AUTHENTICATED | `commerce.api.catalog:configure` | define attribute (`admin-attribute-define`) ✔ Phase 1 |
| POST | `/v1/admin/product-types` | AUTHENTICATED | `commerce.api.catalog:configure` | create product type + draft schema v1 (`admin-product-type-create`) ✔ Phase 1 |
| POST | `/v1/admin/product-types/{productTypeKey}/versions/{version}/publish` | AUTHENTICATED | `commerce.api.catalog:configure` | publish schema version (`admin-product-type-publish`) ✔ Phase 1 |
| POST | `/v1/admin/categories` | AUTHENTICATED | `commerce.api.catalog:configure` | create category |
| POST | `/v1/admin/products` | AUTHENTICATED | `commerce.api.catalog:create` | create product (`admin-product-create`) ✔ Phase 1 |
| GET | `/v1/admin/products/{productId}` | AUTHENTICATED | `commerce.api.catalog:read` | read product (`admin-product-get`) ✔ Phase 1 |
| PUT | `/v1/admin/products/{productId}` | AUTHENTICATED | `commerce.api.catalog:update` | update (revision) |
| POST | `/v1/admin/products/{productId}/publish` | AUTHENTICATED | `commerce.api.catalog:publish` | publish listing |
| GET | `/v1/admin/orders` | AUTHENTICATED | `commerce.api.orders:view` | store orders |
| POST | `/v1/admin/orders/{orderId}/fulfillments` | AUTHENTICATED | `commerce.api.orders:fulfill` | record fulfillment |

✱ Commerce answers 401 to anonymous callers (participant identity required, ADR-0004).

Not through the BFF: `POST /hooks/payments/{provider}` (webhook ingress, ADR-0007).

## Later phases (outline)

- `/v1/seller/**` (HYBRID, seller membership): seller application, offers, seller orders, asset uploads/finalize, earnings, payout requests.
- `/v1/shop/library/**` (HYBRID): entitlements, `POST .../{entitlementId}/downloads` → `{url, expiresAt}`.
- `/v1/admin/sellers/**` (`commerce.api.sellers:*`), `/v1/admin/finance/**` (`commerce.api.finance:*`), `/v1/admin/moderation/**`.

## Implemented operations

Generated from [hive/templates/commerce-service.json](../../hive/templates/commerce-service.json), which `RouteContractTest` checks against the engine's handlers.

| Operation | Method | Path | Access | Hive resource action |
|---|---|---|---|---|
| `admin-attribute-define` | POST | `/v1/admin/attributes` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-product-type-create` | POST | `/v1/admin/product-types` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-product-type-publish` | POST | `/v1/admin/product-types/{productTypeKey}/versions/{version}/publish` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-product-create` | POST | `/v1/admin/products` | AUTHENTICATED | `commerce.api.catalog#create` |
| `admin-product-get` | GET | `/v1/admin/products/{productId}` | AUTHENTICATED | `commerce.api.catalog#read` |
| `admin-category-create` | POST | `/v1/admin/categories` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-category-move` | POST | `/v1/admin/categories/{categoryId}/move` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-category-attributes` | POST | `/v1/admin/categories/{categoryId}/attributes` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-category-archive` | POST | `/v1/admin/categories/{categoryId}/archive` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-product-publish` | POST | `/v1/admin/products/{productId}/publish` | AUTHENTICATED | `commerce.api.catalog#publish` |
| `admin-listing-unpublish` | POST | `/v1/admin/listings/{listingId}/unpublish` | AUTHENTICATED | `commerce.api.catalog#publish` |
| `admin-listing-approve` | POST | `/v1/admin/listings/{listingId}/approve` | AUTHENTICATED | `commerce.api.moderation#decide` |
| `admin-listing-reject` | POST | `/v1/admin/listings/{listingId}/reject` | AUTHENTICATED | `commerce.api.moderation#decide` |
| `store-categories` | GET | `/v1/store/categories` | PUBLIC | — |
| `store-products-search` | GET | `/v1/store/products` | PUBLIC | — (since 0.3.0 each card carries `fromPrice` and `image`) |
| `store-product-detail` | GET | `/v1/store/products/{slug}` | PUBLIC | — |
| `admin-offer-create` | POST | `/v1/admin/offers` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-offer-stock` | POST | `/v1/admin/offers/{offerId}/stock` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-orders` | GET | `/v1/admin/orders` | AUTHENTICATED | `commerce.api.orders#view` |
| `admin-order` | GET | `/v1/admin/orders/{orderId}` | AUTHENTICATED | `commerce.api.orders#view` |
| `admin-order-fulfillments` | GET | `/v1/admin/orders/{orderId}/fulfillments` | AUTHENTICATED | `commerce.api.orders#view` |
| `admin-fulfillment-ship` | POST | `/v1/admin/fulfillments/{fulfillmentId}/ship` | AUTHENTICATED | `commerce.api.orders#fulfill` |
| `admin-fulfillment-deliver` | POST | `/v1/admin/fulfillments/{fulfillmentId}/deliver` | AUTHENTICATED | `commerce.api.orders#fulfill` |
| `admin-order-refund` | POST | `/v1/admin/orders/{orderId}/refunds` | AUTHENTICATED | `commerce.api.orders#refund` |
| `shop-cart-create` | POST | `/v1/shop/carts` | HYBRID | — |
| `shop-cart` | GET | `/v1/shop/carts/current` | HYBRID | — |
| `shop-cart-add` | POST | `/v1/shop/carts/current/items` | HYBRID | — |
| `shop-cart-remove` | DELETE | `/v1/shop/carts/current/items/{itemId}` | HYBRID | — |
| `shop-checkout` | POST | `/v1/shop/checkouts` | HYBRID | — |
| `shop-checkout-get` | GET | `/v1/shop/checkouts/{checkoutId}` | HYBRID | — |
| `shop-orders` | GET | `/v1/shop/orders` | HYBRID | — |
| `shop-order` | GET | `/v1/shop/orders/{orderId}` | HYBRID | — |
| `shop-payment-verify` | POST | `/v1/shop/payments/{paymentId}/verify` | HYBRID | — |
| `admin-asset-create` | POST | `/v1/admin/assets` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-asset-upload` | POST | `/v1/admin/assets/{assetId}/uploads` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-asset-finalize` | POST | `/v1/admin/assets/{assetId}/uploads/{uploadId}/finalize` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-asset-text` | PUT | `/v1/admin/assets/{assetId}/text` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-asset-preview` | PUT | `/v1/admin/assets/{assetId}/preview` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-entitlement-revoke` | POST | `/v1/admin/entitlements/{entitlementId}/revoke` | AUTHENTICATED | `commerce.api.digital#revoke` |
| `shop-library` | GET | `/v1/shop/library` | HYBRID | — |
| `shop-download` | POST | `/v1/shop/library/{entitlementId}/downloads` | HYBRID | — |
| `store-product-previews` | GET | `/v1/store/products/{productId}/previews` | PUBLIC | — |
| `seller-apply` | POST | `/v1/seller/applications` | HYBRID | — |
| `seller-me` | GET | `/v1/seller/me` | HYBRID | — |
| `seller-staff-grant` | POST | `/v1/seller/sellers/{sellerId}/staff` | HYBRID | — |
| `seller-staff-revoke` | DELETE | `/v1/seller/sellers/{sellerId}/staff/{actorId}` | HYBRID | — |
| `seller-submission` | POST | `/v1/seller/sellers/{sellerId}/submissions` | HYBRID | — |
| `seller-listings` | GET | `/v1/seller/sellers/{sellerId}/listings` | HYBRID | — |
| `seller-offer-create` | POST | `/v1/seller/sellers/{sellerId}/offers` | HYBRID | — |
| `seller-offers` | GET | `/v1/seller/sellers/{sellerId}/offers` | HYBRID | — |
| `seller-orders` | GET | `/v1/seller/sellers/{sellerId}/orders` | HYBRID | — |
| `seller-fulfillments` | GET | `/v1/seller/sellers/{sellerId}/fulfillments` | HYBRID | — |
| `seller-fulfillment-ship` | POST | `/v1/seller/fulfillments/{fulfillmentId}/ship` | HYBRID | — |
| `seller-asset-create` | POST | `/v1/seller/sellers/{sellerId}/assets` | HYBRID | — |
| `seller-asset-upload` | POST | `/v1/seller/assets/{assetId}/uploads` | HYBRID | — |
| `seller-asset-finalize` | POST | `/v1/seller/assets/{assetId}/uploads/{uploadId}/finalize` | HYBRID | — |
| `seller-asset-text` | PUT | `/v1/seller/assets/{assetId}/text` | HYBRID | — |
| `seller-asset-preview` | PUT | `/v1/seller/assets/{assetId}/preview` | HYBRID | — |
| `seller-finance` | GET | `/v1/seller/sellers/{sellerId}/finance` | HYBRID | — |
| `seller-payout-request` | POST | `/v1/seller/sellers/{sellerId}/payouts` | HYBRID | — |
| `admin-sellers` | GET | `/v1/admin/sellers` | AUTHENTICATED | `commerce.api.sellers#view` |
| `admin-seller-verify` | POST | `/v1/admin/sellers/{sellerId}/verify` | AUTHENTICATED | `commerce.api.sellers#approve` |
| `admin-seller-approve` | POST | `/v1/admin/sellers/{sellerId}/approve` | AUTHENTICATED | `commerce.api.sellers#approve` |
| `admin-seller-suspend` | POST | `/v1/admin/sellers/{sellerId}/suspend` | AUTHENTICATED | `commerce.api.sellers#suspend` |
| `admin-seller-reinstate` | POST | `/v1/admin/sellers/{sellerId}/reinstate` | AUTHENTICATED | `commerce.api.sellers#suspend` |
| `admin-listings` | GET | `/v1/admin/listings` | AUTHENTICATED | `commerce.api.moderation#review` |
| `admin-store` | GET | `/v1/admin/store` | AUTHENTICATED | `commerce.api.stores#view` |
| `admin-store-mode` | POST | `/v1/admin/store/mode` | AUTHENTICATED | `commerce.api.stores#configure` |
| `admin-commission-rule-create` | POST | `/v1/admin/finance/commission-rules` | AUTHENTICATED | `commerce.api.finance#settle` |
| `admin-commission-rules` | GET | `/v1/admin/finance/commission-rules` | AUTHENTICATED | `commerce.api.finance#view` |
| `admin-finance-balances` | GET | `/v1/admin/finance/balances` | AUTHENTICATED | `commerce.api.finance#view` |
| `admin-settlement-close` | POST | `/v1/admin/finance/settlements` | AUTHENTICATED | `commerce.api.finance#settle` |
| `admin-settlements` | GET | `/v1/admin/finance/settlements` | AUTHENTICATED | `commerce.api.finance#view` |
| `admin-payouts` | GET | `/v1/admin/finance/payouts` | AUTHENTICATED | `commerce.api.finance#view` |
| `admin-payout-approve` | POST | `/v1/admin/finance/payouts/{payoutId}/approve` | AUTHENTICATED | `commerce.api.finance#approve_payout` |
| `admin-payout-reject` | POST | `/v1/admin/finance/payouts/{payoutId}/reject` | AUTHENTICATED | `commerce.api.finance#approve_payout` |
| `admin-payout-paid` | POST | `/v1/admin/finance/payouts/{payoutId}/paid` | AUTHENTICATED | `commerce.api.finance#approve_payout` |
| `admin-attributes` | GET | `/v1/admin/attributes` | AUTHENTICATED | `commerce.api.catalog#read` |
| `admin-product-types` | GET | `/v1/admin/product-types` | AUTHENTICATED | `commerce.api.catalog#read` |
| `admin-products` | GET | `/v1/admin/products` | AUTHENTICATED | `commerce.api.catalog#read` |
| `admin-extension-get` | GET | `/v1/admin/extensions/{extensionKey}/{subjectType}/{subjectId}` | AUTHENTICATED | `commerce.api.catalog#read` |
| `admin-extension-put` | PUT | `/v1/admin/extensions/{extensionKey}/{subjectType}/{subjectId}` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-product-type-draft` | POST | `/v1/admin/product-types/{productTypeKey}/versions` | AUTHENTICATED | `commerce.api.catalog#configure` |
| `admin-product-migrate` | POST | `/v1/admin/products/{productId}/schema-migration` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-product-media` | PUT | `/v1/admin/products/{productId}/media` | AUTHENTICATED | `commerce.api.catalog#update` |
| `admin-category-seo` | PUT | `/v1/admin/categories/{categoryId}/seo` | AUTHENTICATED | `commerce.api.catalog#update` |
| `seller-assets` | GET | `/v1/seller/sellers/{sellerId}/assets` | HYBRID | — |
| `admin-payments` | GET | `/v1/admin/payments` | AUTHENTICATED | `commerce.api.orders#view` |
| `admin-customers` | GET | `/v1/admin/customers` | AUTHENTICATED | `commerce.api.customers#view` |
| `store-product-reviews` | GET | `/v1/store/products/{productId}/reviews` | PUBLIC | — |
| `shop-review-write` | POST | `/v1/shop/products/{productId}/reviews` | HYBRID | — |
| `admin-reviews` | GET | `/v1/admin/reviews` | AUTHENTICATED | `commerce.api.moderation#review` |
| `admin-review-hide` | POST | `/v1/admin/reviews/{reviewId}/hide` | AUTHENTICATED | `commerce.api.moderation#decide` |
| `admin-store-capabilities` | GET | `/v1/admin/store/capabilities` | AUTHENTICATED | `commerce.api.stores#view` |

Admin modules, one per area, each with a PAGE resource `commerce.admin.<area>`: `catalog` (Categories, Attributes, Product Types, Products), `orders`, `payments`, `customers`, `sellers` (Sellers, Listings, Reviews), `finance` (Commissions, Settlements, Payouts), `digital` (Digital Assets), `settings` (Stores, Settings, extension documents).
