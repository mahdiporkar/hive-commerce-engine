package io.hivecommerce.catalog.domain;

import static io.hivecommerce.catalog.domain.CatalogFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductTypeTest {
  @Test
  void createsDraftOneAndPublishesItOnce() {
    var type = ProductType.create(Ids.newId(), SCOPE, "pottery", LocalizedText.of("en", "Pottery"), List.of(new AttributeSpec(MATERIAL, true, false)));
    assertThat(type.latestPublished()).isEmpty();
    assertThat(type.draft()).hasValueSatisfying(d -> assertThat(d.number()).isEqualTo(1));
    type.publish(1, NOW);
    assertThat(type.latestPublished()).hasValueSatisfying(p -> assertThat(p.publishedAt()).isEqualTo(NOW));
    assertThat(type.pullEvents()).hasSize(1);
    assertThatThrownBy(() -> type.publish(1, NOW)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("SCHEMA_VERSION_PUBLISHED"));
  }

  @Test
  void newVersionIsADraftAndOldVersionStaysUnchanged() {
    var type = publishedType(List.of(new AttributeSpec(MATERIAL, true, false)));
    var v2 = type.draftNextVersion(List.of(new AttributeSpec(MATERIAL, true, false), new AttributeSpec(NOTE, false, false)));
    assertThat(v2.number()).isEqualTo(2);
    assertThat(type.latestPublished().orElseThrow().number()).isEqualTo(1);
    assertThat(type.latestPublished().orElseThrow().specs()).hasSize(1);
    assertThatThrownBy(() -> type.draftNextVersion(List.of())).isInstanceOf(DomainRuleException.class);
  }

  @Test
  void onlyEnumOrColorMayDefineVariants() {
    assertThatThrownBy(() -> ProductType.create(Ids.newId(), SCOPE, "bad", LocalizedText.of("en", "Bad"), List.of(new AttributeSpec(WEIGHT, true, true))))
        .isInstanceOf(DomainValidationException.class);
  }

  @Test
  void rejectsDuplicateAttributesAndBadKeys() {
    assertThatThrownBy(() -> ProductType.create(Ids.newId(), SCOPE, "Bad Key", LocalizedText.of("en", "x"),
        List.of(new AttributeSpec(MATERIAL, true, false), new AttributeSpec(MATERIAL, false, false))))
        .isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.violations()).hasSize(2));
  }
}
