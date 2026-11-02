package io.hivecommerce.catalog.application.published;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog's published in-process API (ADR-0002): the only Catalog types other contexts may use. Callers must already
 * be inside a scoped transaction ({@code TenantTransactions.inScope}); these are read-only.
 */
public interface CatalogQueries {

  /** What other contexts need to know about a product: identity, presentation, kind and filterable facets. */
  record ProductSnapshot(UUID productId, UUID categoryId, String productTypeKey, String kind, Map<String, String> title, String slug,
      Map<String, Object> attributes, List<String> filterableKeys, List<VariantSnapshot> variants) {}

  record VariantSnapshot(UUID variantId, UUID productId, String sku, Map<String, Object> attributes) {}

  record CategoryNode(UUID id, UUID parentId, String slug, Map<String, String> name, List<String> attributes, int position, int depth) {}

  Optional<ProductSnapshot> product(TenantScope scope, UUID productId);

  Optional<VariantSnapshot> variant(TenantScope scope, UUID variantId);

  /** The category and its active descendants; empty when the category is unknown or archived. */
  List<UUID> categorySubtree(TenantScope scope, UUID categoryId);

  List<CategoryNode> activeCategories(TenantScope scope);
}
