package io.hivecommerce.listing.application;

import io.hivecommerce.catalog.application.published.CatalogQueries.ProductSnapshot;
import io.hivecommerce.listing.application.ListingProjection.Facet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Turns filterable attribute values (product and variant level) into typed facet rows. */
final class Facets {
  private Facets() {}

  static List<Facet> of(ProductSnapshot product) {
    var facets = new LinkedHashSet<Facet>();
    for (String key : product.filterableKeys()) {
      add(facets, key, product.attributes().get(key));
      product.variants().forEach(v -> add(facets, key, v.attributes().get(key)));
    }
    return new ArrayList<>(facets);
  }

  private static void add(LinkedHashSet<Facet> facets, String key, Object value) {
    switch (value) {
      case null -> { }
      case String s -> facets.add(new Facet(key, s, null));
      case Boolean b -> facets.add(new Facet(key, b.toString(), null));
      case BigDecimal d -> facets.add(new Facet(key, null, d));
      case Number n -> facets.add(new Facet(key, null, new BigDecimal(n.toString())));
      case List<?> list -> list.forEach(item -> add(facets, key, item));
      case Map<?, ?> map when map.get("value") != null -> add(facets, key, map.get("value"));
      default -> { }
    }
  }
}
