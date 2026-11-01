package io.hivecommerce.catalog.application;

import io.hivecommerce.catalog.domain.AttributeConstraints;
import io.hivecommerce.catalog.domain.AttributeFlags;
import io.hivecommerce.catalog.domain.AttributeOption;
import io.hivecommerce.catalog.domain.AttributeType;
import io.hivecommerce.catalog.domain.Product;
import io.hivecommerce.shared.LocalizedText;
import java.util.List;
import java.util.Map;

/** Commands accepted by the Catalog application services (already parsed into domain value objects by the API layer). */
public final class CatalogCommands {
  private CatalogCommands() {}

  public record DefineAttribute(String key, AttributeType type, LocalizedText labels, List<AttributeOption> options,
      AttributeConstraints constraints, AttributeFlags flags) {}

  public record SpecReference(String key, boolean required, boolean variantDefining) {}

  public record CreateProductType(String key, LocalizedText name, List<SpecReference> attributes) {}

  public record CreateProduct(String productType, LocalizedText title, String slug, Map<String, Object> attributes, List<Product.VariantDraft> variants) {}
}
