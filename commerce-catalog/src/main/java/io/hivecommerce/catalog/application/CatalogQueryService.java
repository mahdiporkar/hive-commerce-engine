package io.hivecommerce.catalog.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.catalog.domain.AttributeDefinition;
import io.hivecommerce.catalog.domain.CatalogRepositories;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Implements Catalog's published query API on top of the Catalog repositories. */
@Service
class CatalogQueryService implements CatalogQueries {
  private final CatalogRepositories.Products products;
  private final CatalogRepositories.ProductTypes productTypes;
  private final CatalogRepositories.AttributeDefinitions attributes;
  private final CatalogRepositories.Categories categories;

  CatalogQueryService(CatalogRepositories.Products products, CatalogRepositories.ProductTypes productTypes,
      CatalogRepositories.AttributeDefinitions attributes, CatalogRepositories.Categories categories) {
    this.products = products;
    this.productTypes = productTypes;
    this.attributes = attributes;
    this.categories = categories;
  }

  @Override
  public Optional<ProductSnapshot> product(TenantScope scope, UUID productId) {
    return products.findById(scope, productId).flatMap(p -> productTypes.findById(scope, p.productTypeId()).map(type -> {
      List<String> keys = p.attributes().keySet().stream().toList();
      List<String> variantKeys = p.variants().stream().flatMap(v -> v.attributes().keySet().stream()).distinct().toList();
      var all = new java.util.ArrayList<>(keys);
      variantKeys.forEach(k -> { if (!all.contains(k)) all.add(k); });
      List<String> filterable = attributes.findByKeys(scope, all).stream().filter(d -> d.flags().filterable()).map(AttributeDefinition::key).sorted().toList();
      return new ProductSnapshot(p.id(), p.categoryId(), type.key(), type.kind().name(), p.title().values(), p.slug().value(), p.attributes(), filterable,
          p.variants().stream().map(v -> new VariantSnapshot(v.id(), p.id(), v.sku(), v.attributes())).toList());
    }));
  }

  @Override
  public Optional<VariantSnapshot> variant(TenantScope scope, UUID variantId) {
    return products.findByVariantId(scope, variantId)
        .flatMap(p -> p.variants().stream().filter(v -> v.id().equals(variantId)).findFirst()
            .map(v -> new VariantSnapshot(v.id(), p.id(), v.sku(), v.attributes())));
  }

  @Override
  public List<UUID> categorySubtree(TenantScope scope, UUID categoryId) {
    return categories.subtreeIds(scope, categoryId);
  }

  @Override
  public List<CategoryNode> activeCategories(TenantScope scope) {
    return categories.findActive(scope).stream()
        .map(c -> new CategoryNode(c.id(), c.parentId(), c.slug().value(), c.name().values(), c.attributeKeys(), c.position(), c.depth())).toList();
  }
}
