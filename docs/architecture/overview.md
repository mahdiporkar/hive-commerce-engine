# Architecture overview

> Platform owns capabilities. Solution owns business. Consumer owns presentation.

Hive is the platform. Hive Commerce Engine (this repository) is a reusable solution layer. Each client repository (gemstone-commerce, aidastoor-commerce) is a solution plus consumer built on it. Hive facts used here are verified in [hive-integration](hive-integration.md).

## System context

```mermaid
flowchart TB
  shopper([Shoppers / visitors]) --> sf[Client storefront<br/>Next.js SSR, repo B or C]
  seller([Sellers]) --> sf
  staff([Marketplace operators]) --> shell[Hive shell + commerce admin MFEs]
  sf -->|same origin: /api, /auth| bff[Hive BFF<br/>one Hive install per client, ADR-0003]
  shell --> bff
  bff --> idp[Client OIDC provider]
  bff -->|/api/routes/commerce/** FORWARD_TOKEN| eng[Hive Commerce Engine<br/>one deployment per client]
  psp[Payment provider] -->|signed webhook /hooks/payments/*| eng
  eng --> pg[(PostgreSQL per client)]
  eng --> s3[(S3 / MinIO private bucket per client)]
  shopper -.->|pre-signed upload / download| s3
```

## Bounded contexts (context map)

```mermaid
flowchart LR
  subgraph Core[Commerce core - repository A]
    CFG[Configuration<br/>mode, capabilities, currencies]
    STO[Store & Channel]
    SEL[Seller & Membership]
    CAT[Catalog<br/>categories, product types, attributes,<br/>products, variants, listings]
    OFF[Offers & Pricing]
    INV[Inventory & Reservations]
    CART[Cart & Checkout]
    ORD[Orders & Seller Orders]
    PAY[Payments & Refunds]
    FUL[Fulfillment & Shipments]
    FIN[Commission, Ledger,<br/>Settlement, Payout]
    DIG[Digital assets, Licenses,<br/>Entitlements]
  end
  EXT[Extension SPI<br/>gemstone verification,<br/>prompt verification]
  CAT --> OFF --> CART --> ORD
  INV --> CART
  ORD --> PAY
  ORD --> FUL
  ORD --> DIG
  PAY --> FIN
  ORD --> FIN
  SEL --> OFF
  SEL --> FIN
  STO --> CAT
  CFG --> STO
  EXT -.->|SPI| CAT
  EXT -.->|SPI| SEL
```

Relationships: Catalog is upstream of Offers, Cart and Orders, which consume snapshots (published language: `ListingSnapshot`, `PriceQuote`). Orders publish `OrderPaid`, `OrderLineRefunded` and similar events through the transactional outbox; Finance, Fulfillment and Digital conform to those events.

## Purchase sequence (physical, single vendor)

```mermaid
sequenceDiagram
  participant B as Browser
  participant F as Hive BFF
  participant E as Commerce Engine
  participant P as Payment provider
  B->>F: POST /api/routes/commerce/v1/shop/checkouts (session, CSRF)
  F->>E: HYBRID op, Bearer user token, X-Hive-User-Id
  E->>E: validate JWT, recalc prices, reserve stock (row lock, TTL)
  E-->>B: checkout {id, total}
  B->>F: POST .../checkouts/{id}/payments {idempotencyKey}
  F->>E: forward
  E->>P: initiate
  E-->>B: {redirectUrl}
  B->>P: pay
  P-->>B: redirect to storefront /checkout/return?attempt=…
  P--)E: signed webhook /hooks/payments/{provider} (direct, not via BFF)
  E->>E: inbox dedupe → confirmPayment (idempotent)
  B->>F: POST .../payments/{attemptId}/verify
  F->>E: forward
  E->>P: verify(providerRef)
  E->>E: confirmPayment (no-op if already confirmed) → Order PAID, ledger journal
  E-->>B: order {status: PAID}
```

## Authentication and two-layer authorization

```mermaid
sequenceDiagram
  participant B as Browser
  participant F as Hive BFF
  participant A as Hive authorization
  participant E as Commerce Engine
  B->>F: POST /api/routes/commerce/v1/admin/products (session, CSRF)
  F->>A: resolve(POST, /commerce/v1/admin/products, userId)
  A-->>F: AUTHENTICATED, commerce.api.catalog:create → ALLOWED / DENIED
  alt Layer 1 denied
    F-->>B: 403 ACCESS_DENIED (engine never contacted)
  else allowed
    F->>E: Bearer token, X-Hive-User-Id, X-Hive-Tenant-Id, X-Correlation-Id
    E->>E: Layer 2: JWT valid? tenant matches? user administers this store?
    E-->>B: 201 / 403 COMMERCE_SCOPE_DENIED
  end
```

Further diagrams are produced in the phase that implements them: digital upload/download (Phase 3), multi-seller order processing (Phase 4).

## Deployment per client

One Hive install (gateway/BFF, authorization, OpenFGA, PostgreSQL, Redis) + one engine container (API port on the private network, webhook port exposed only via the `/hooks/` ingress rule) + PostgreSQL + S3 bucket + the client storefront (Next.js server, which proxies `/api`, `/auth`, `/oauth2` and `/login/oauth2` to the BFF on the same origin).
