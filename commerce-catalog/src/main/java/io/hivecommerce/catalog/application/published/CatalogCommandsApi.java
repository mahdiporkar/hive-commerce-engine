package io.hivecommerce.catalog.application.published;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Catalog commands other contexts may issue: a seller submission creates a product on behalf of a member. */
public interface CatalogCommandsApi {
  record VariantDraft(String sku, Map<String, Object> attributes) {}

  record ProductDraft(String productType, UUID categoryId, Map<String, String> title, String slug, Map<String, Object> attributes, List<VariantDraft> variants) {}

  /** @return the new product id. The caller has already authorized the actor (Layer 2) and is inside a scoped transaction. */
  UUID createProduct(TenantScope scope, UUID actor, ProductDraft draft);
}
