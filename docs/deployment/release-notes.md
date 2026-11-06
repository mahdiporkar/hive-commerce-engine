# Release notes

Neither version below has been published yet. Both exist as locally packaged releases (`tools/package-release.sh`).

## 0.3.0

Backward compatible with 0.2.0: one additive response change, no migration.

**Added**

- Search results (`GET /v1/store/products`) carry `fromPrice` (lowest active offer: `{amount, currency}`) and `image` (first product
  image: `{url, kind, alt}`) on each card, both `null` when the product has none. They are read at query time, so a price or
  media change shows at once. Storefronts no longer need one detail request per card to show a price or a picture.
- SDK: the two optional fields on `Card`.
- Builder: `seedCatalog` sets a product's `media` when the catalog file lists it.

**Migrations**: none.

## 0.2.0

Backward compatible with 0.1.0. A store on 0.1.0 can upgrade and roll back on the same database.

**Added**

- Schema evolution: `POST /v1/admin/product-types/{key}/versions` drafts a new version, and
  `POST /v1/admin/products/{id}/schema-migration` moves one product explicitly. Listed products update their public page.
- Public product media (`PUT /v1/admin/products/{id}/media`) on the product page and in JSON-LD.
- Category search metadata (`PUT /v1/admin/categories/{id}/seo`).
- Verified-purchase reviews, off by default (`COMMERCE_CAP_REVIEWS`): public reads, customer writes, operator moderation.
- Operator reads of customers (`GET /v1/admin/customers`), payments (`GET /v1/admin/payments`) and capabilities
  (`GET /v1/admin/store/capabilities`).
- Seller assets (`GET /v1/seller/sellers/{id}/assets`). Seller listings now include slug, title and variant ids.
- Admin bundles: Payments and Customers modules; schema versions, migration, media and category metadata in Catalog; review
  moderation in Sellers; capabilities and a generic extension document editor in Settings. Modules talk through
  `commerce:product-selected` and take colors from Hive's theme tokens.
- SDK: reviews, seller assets, direct file upload, Hive public context, `finance(currency)`.
- Builder: theme contrast checks, `set`, `schema-diff`.

**Migrations (all expand-only)**: `catalog/V3` (media and metadata columns), `digital/V2` (index), `ordering/V3` (reviews table).

**Hive**: 12 new route operations. Roles gain the new admin pages. `register.mjs` adds them and keeps what exists.

**Changed**: admin bundle addresses carry their version (`<host>/<version>/<module>/entry.js`), and a store's host serves
several versions side by side. Before this, a host could serve only one version, so neither an upgrade nor a rollback of the
admin UI could work. The upgrade test found this.

**Fixed**: the OpenAPI document no longer collapses the catalog and digital `VersionView` schemas.

## 0.1.0

First packaged version: Phases 1 to 5 of the [roadmap](../roadmap.md). Engine, extension SPI, six admin bundles, Hive
templates and registration, storefront SDK, Commerce Builder, test kit.
