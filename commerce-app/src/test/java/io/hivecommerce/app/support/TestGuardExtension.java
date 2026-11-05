package io.hivecommerce.app.support;

import io.hivecommerce.spi.ExtensionData;
import io.hivecommerce.spi.ExtensionDataHandler;
import io.hivecommerce.spi.Issue;
import io.hivecommerce.spi.ListingApprovalExtension;
import io.hivecommerce.spi.ProductFacts;
import io.hivecommerce.spi.ProductPresentationExtension;
import io.hivecommerce.spi.ProductValidationExtension;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only extension, discovered through META-INF/services like a client's. It acts only on product types whose key
 * starts with {@code guarded-}, so other tests see an engine behaving as if no extension were installed.
 */
public class TestGuardExtension implements ProductValidationExtension, ListingApprovalExtension, ProductPresentationExtension, ExtensionDataHandler {
  @Override public String key() { return "test-guard"; }

  private static boolean guarded(ProductFacts product) { return product.productType().startsWith("guarded-"); }

  @Override
  public List<Issue> validate(ProductFacts product) {
    if (product.productType().equals("guarded-boom")) throw new IllegalStateException("extension bug");
    return guarded(product) && "forbidden".equals(product.attributes().get("note")) ? List.of(new Issue("attributes.note", "this value is refused")) : List.of();
  }

  @Override
  public List<Issue> reviewForPublication(UUID productId, ProductFacts product, ExtensionData data) {
    if (!guarded(product)) return List.of();
    boolean passed = data.get("product", productId.toString()).map(d -> "PASSED".equals(d.get("inspection"))).orElse(false);
    return passed ? List.of() : List.of(new Issue("inspection", "an inspection must pass before publication"));
  }

  @Override
  public Map<String, Object> present(UUID productId, ProductFacts product, ExtensionData data) {
    if (!guarded(product)) return Map.of();
    return data.get("product", productId.toString()).map(d -> Map.<String, Object>of("inspection", d.get("inspection"))).orElse(Map.of());
  }

  @Override
  public List<Issue> validateData(String subjectType, String subjectId, Map<String, Object> document) {
    return document.containsKey("inspection") ? List.of() : List.of(new Issue("inspection", "is required"));
  }
}
