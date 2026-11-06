# Creating a store

The Commerce Builder (`builder/`) creates a store repository from a configuration file. It covers stores, schemas,
capabilities and themes. It is not a visual site builder: the storefront it generates is ordinary Next.js code that the
store then owns.

## 1. Describe the store

`store.json`:

```json
{
  "key": "handicraft",
  "displayName": {"fa": "هنر دست", "en": "Hand Made"},
  "locales": ["fa", "en"],
  "defaultLocale": "fa",
  "currency": "IRR",
  "mode": "SINGLE_VENDOR",
  "capabilities": {"physicalProducts": true, "digitalProducts": false, "sellerOnboarding": false, "commissions": false, "reviews": true},
  "theme": {"accent": "#7f1d3a", "accentText": "#ffffff", "surface": "#fbf8f4", "surfaceAlt": "#ffffff", "text": "#2b1d22", "muted": "#6e5c63", "border": "#e4d9d2", "radius": 10},
  "domain": "handicraft.example",
  "portBase": 30300
}
```

`taxonomy.json` holds attributes, product types and categories, and `products.json` holds sample products.
[fixtures/handicraft/catalog.json](../../fixtures/handicraft/catalog.json) shows the format.

## 2. Check and generate

```bash
commerce-builder validate --store store.json --taxonomy taxonomy.json --products products.json
commerce-builder new-store --store store.json --taxonomy taxonomy.json --products products.json --out ../handicraft-commerce --engine-version 0.2.0
```

`validate` finds configuration problems before anything reaches an engine: capabilities that contradict the mode, broken
schema references, required values missing from sample products, and themes with too little contrast to read.

`new-store` refuses to write over an existing store. The result holds no engine source. It pins the engine by version.

## 3. Change it later

```bash
commerce-builder set --store storefront/config/store.json capabilities.reviews=true theme.accent=#0f5e57
commerce-builder schema-diff --from old-type.json --to new-type.json
```

`set` validates before saving, so a bad edit leaves the file alone. After changing capabilities, regenerate `deploy/store.env`
with `commerce-builder env`. `schema-diff` says what a schema change means for existing products. A change to a live product
type is a new schema version, in the admin Catalog module or through `POST /v1/admin/product-types/{key}/versions`. Products
keep their version until they are migrated one at a time, and a product that lacks a newly required value says so.

## 4. Run it

```bash
ENGINE_RELEASE_DIR=<dir> node tools/fetch-engine.mjs   # omit the variable to use the published release
(cd storefront && npm install)
(cd tests && npm install)
node tools/build-extensions.mjs
node --test tests/contract/*.test.mjs
node --test tests/e2e/acceptance.test.mjs
```

To deploy, copy `deploy/env.example` to `deploy/.env` with real values from your secret store, then
`docker compose -f deploy/compose.yml up`. Each store has its own Hive, database, bucket and domain.

## 5. Add what is specific to the store

| What | Where |
|---|---|
| Branding and pages | `storefront/` |
| Rules and data an industry needs | An extension in `extensions/`, through the [SPI](../architecture/extension-system.md) |
| How an extension appears on the product page | `storefront/components/ProductExtras.tsx` |
| Journeys only this store has | `tests/e2e/journeys.mjs` |

Catalog, cart, checkout, orders, payments, sellers, commissions and settlements stay in the engine.
