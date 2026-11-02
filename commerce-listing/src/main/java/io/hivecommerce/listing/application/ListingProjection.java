package io.hivecommerce.listing.application;

import io.hivecommerce.catalog.application.published.CatalogQueries.ProductSnapshot;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public search read model of published listings (ADR-0010's projection, owned by Listing because only published
 * listings are searchable). Derived data: rebuilt from snapshots, so facet rows may be replaced.
 */
public interface ListingProjection {
  record Facet(String key, String text, BigDecimal number) {}

  void upsert(TenantScope scope, UUID listingId, ProductSnapshot product, List<Facet> facets);

  void markUnpublished(TenantScope scope, UUID listingId);

  record SearchQuery(List<UUID> categoryIds, String text, Map<String, List<String>> equals, Map<String, BigDecimal> min, Map<String, BigDecimal> max,
      String sort, int page, int size) {}

  record Card(UUID listingId, UUID productId, String slug, Map<String, String> title, UUID categoryId, String kind, Map<String, Object> attributes) {}

  record FacetCount(String key, String value, long count) {}

  record Page(List<Card> items, long total, int page, int size, List<FacetCount> facets) {}

  Page search(TenantScope scope, SearchQuery query);

  record Detail(UUID listingId, UUID productId, String slug, Map<String, String> title, UUID categoryId, String kind, Map<String, Object> attributes,
      List<Map<String, Object>> variants) {}

  java.util.Optional<Detail> bySlug(TenantScope scope, String slug);
}
