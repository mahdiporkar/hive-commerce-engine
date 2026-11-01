# ADR-0010 — Catalog attributes: JSONB, versioned schemas, projection

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Decision
- `product_type` has immutable `product_type_schema_version` rows: a JSON Schema 2020-12 subset generated from the attribute definitions. Each product references the schema version it was validated against.
- Attribute values are JSONB on `product` and `product_variant`, validated server-side against that version.
- Filterable and sortable attributes are projected into `product_search_value(store_id, product_id, attribute_key, text_value, num_value, bool_value, date_value)` with indexes, and rebuilt from events. A search engine can replace this later behind a `CatalogSearch` port.
- A schema change creates a new version. Migrating products to it is an explicit job that reports its results.
- Price, stock, reservations and licenses are never attributes.

## Alternatives
Classic EAV: rejected (unbounded joins). One table per product type: rejected (requires DDL for each configuration change).
