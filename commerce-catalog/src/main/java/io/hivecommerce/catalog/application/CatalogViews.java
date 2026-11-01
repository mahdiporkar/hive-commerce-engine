package io.hivecommerce.catalog.application;

import io.hivecommerce.catalog.domain.AttributeDefinition;
import io.hivecommerce.catalog.domain.AttributeSpec;
import io.hivecommerce.catalog.domain.Product;
import io.hivecommerce.catalog.domain.ProductType;
import io.hivecommerce.catalog.domain.SchemaVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models returned by the Catalog application services. They never expose aggregates to the API layer. */
public final class CatalogViews {
  private CatalogViews() {}

  public record AttributeView(UUID id, String key, String type, Map<String, String> labels, List<String> options, long revision) {
    static AttributeView of(AttributeDefinition d) {
      return new AttributeView(d.id(), d.key(), d.type().name(), d.labels().values(), d.rule().optionCodes(), d.revision());
    }
  }

  public record SpecView(String key, String type, boolean required, boolean variantDefining) {
    static SpecView of(AttributeSpec s) { return new SpecView(s.key(), s.rule().type().name(), s.required(), s.variantDefining()); }
  }

  public record VersionView(int version, String status, Instant publishedAt, List<SpecView> attributes) {
    static VersionView of(SchemaVersion v) {
      return new VersionView(v.number(), v.status().name(), v.publishedAt(), v.specs().stream().map(SpecView::of).toList());
    }
  }

  public record ProductTypeView(UUID id, String key, Map<String, String> name, List<VersionView> versions, long revision) {
    static ProductTypeView of(ProductType t) {
      return new ProductTypeView(t.id(), t.key(), t.name().values(), t.versions().stream().map(VersionView::of).toList(), t.revision());
    }
  }

  public record VariantView(UUID id, String sku, Map<String, Object> attributes) {}

  public record ProductView(UUID id, UUID productTypeId, int schemaVersion, Map<String, String> title, String slug, Map<String, Object> attributes,
      List<VariantView> variants, String status, long revision) {
    static ProductView of(Product p) {
      return new ProductView(p.id(), p.productTypeId(), p.schemaVersion(), p.title().values(), p.slug().value(), p.attributes(),
          p.variants().stream().map(v -> new VariantView(v.id(), v.sku(), v.attributes())).toList(), p.status().name(), p.revision());
    }
  }
}
