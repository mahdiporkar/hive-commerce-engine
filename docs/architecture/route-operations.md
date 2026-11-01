# Route operations and resource vocabulary (draft)

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
