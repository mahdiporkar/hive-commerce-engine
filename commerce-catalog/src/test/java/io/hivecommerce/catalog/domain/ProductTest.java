package io.hivecommerce.catalog.domain;

import static io.hivecommerce.catalog.domain.CatalogFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.DomainViolation;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {
  private static final UUID ACTOR = UUID.randomUUID();

  private static Product create(ProductType type, Map<String, Object> attributes, List<Product.VariantDraft> variants) {
    return Product.create(Ids.newId(), SCOPE, type, LocalizedText.of("en", "Bowl"), "bowl", attributes, variants, ACTOR, Ids::newId, NOW);
  }

  @Test
  void createsProductWithNormalizedAttributesAndVariants() {
    var type = publishedType(List.of(new AttributeSpec(MATERIAL, true, false), new AttributeSpec(WEIGHT, false, false), new AttributeSpec(COLOR, true, true)));
    var product = create(type, Map.of("material", "clay", "weight", Map.of("value", 350, "unit", "g")),
        List.of(new Product.VariantDraft("BOWL-RED", Map.of("color", "red")), new Product.VariantDraft("BOWL-BLUE", Map.of("color", "blue"))));
    assertThat(product.attributes()).containsEntry("weight", Map.of("value", new BigDecimal("350"), "unit", "g"));
    assertThat(product.variants()).extracting(Product.Variant::sku).containsExactly("BOWL-RED", "BOWL-BLUE");
    assertThat(product.schemaVersion()).isEqualTo(1);
    assertThat(product.pullEvents()).singleElement().satisfies(e -> assertThat(e.type()).isEqualTo("commerce.catalog.ProductCreated"));
  }

  @Test
  void reportsEveryViolationAtOnce() {
    var type = publishedType(List.of(new AttributeSpec(MATERIAL, true, false), new AttributeSpec(COLOR, true, true)));
    assertThatThrownBy(() -> create(type, Map.of("unknown", 1),
        List.of(new Product.VariantDraft("bad sku", Map.of("color", "green")), new Product.VariantDraft("OK-1", Map.of()))))
        .isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.violations()).extracting(DomainViolation::field)
            .contains("attributes.unknown", "attributes.material", "variants[0].sku", "variants[0].attributes.color", "variants[1].attributes.color"));
  }

  @Test
  void variantCombinationsMustBeUnique() {
    var type = publishedType(List.of(new AttributeSpec(COLOR, true, true)));
    assertThatThrownBy(() -> create(type, Map.of(), List.of(new Product.VariantDraft("A", Map.of("color", "red")), new Product.VariantDraft("B", Map.of("color", "red")))))
        .isInstanceOf(DomainValidationException.class);
  }

  @Test
  void typeWithoutVariantAttributesAllowsExactlyOneVariant() {
    var type = publishedType(List.of(new AttributeSpec(MATERIAL, true, false)));
    assertThat(create(type, Map.of("material", "wood"), List.of(new Product.VariantDraft("ONE", Map.of()))).variants()).hasSize(1);
    assertThatThrownBy(() -> create(type, Map.of("material", "wood"), List.of(new Product.VariantDraft("A", Map.of()), new Product.VariantDraft("B", Map.of()))))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> create(type, Map.of("material", "wood"), List.of())).isInstanceOf(DomainValidationException.class);
  }

  @Test
  void requiresAPublishedSchemaInTheSameScope() {
    var draftOnly = ProductType.create(Ids.newId(), SCOPE, "draft-only", LocalizedText.of("en", "Draft"), List.of());
    assertThatThrownBy(() -> create(draftOnly, Map.of(), List.of(new Product.VariantDraft("A", Map.of()))))
        .isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("SCHEMA_NOT_PUBLISHED"));
    var foreign = publishedType(List.of());
    var otherScope = new TenantScope("other", UUID.randomUUID());
    assertThatThrownBy(() -> Product.create(Ids.newId(), otherScope, foreign, LocalizedText.of("en", "x"), "x", Map.of(),
        List.of(new Product.VariantDraft("A", Map.of())), ACTOR, Ids::newId, NOW)).isInstanceOf(DomainRuleException.class);
  }
}
