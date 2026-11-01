package io.hivecommerce.catalog.domain;

import static io.hivecommerce.catalog.domain.CatalogFixtures.NOW;
import static io.hivecommerce.catalog.domain.CatalogFixtures.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttributeDefinitionTest {
  private static AttributeDefinition define(String key, AttributeType type, List<AttributeOption> options, AttributeConstraints constraints) {
    return AttributeDefinition.define(Ids.newId(), SCOPE, key, type, LocalizedText.of("en", key), options, constraints, null, NOW);
  }

  @Test
  void enumNeedsUniqueOptionsAndSnapshotsThem() {
    var material = define("material", AttributeType.ENUM,
        List.of(new AttributeOption("wood", LocalizedText.of("fa", "چوب")), new AttributeOption("clay", LocalizedText.of("fa", "سفال"))), null);
    assertThat(material.rule().optionCodes()).containsExactly("wood", "clay");
    assertThat(material.pullEvents()).hasSize(1);
    assertThatThrownBy(() -> define("material", AttributeType.ENUM, List.of(), null)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> define("material", AttributeType.ENUM,
        List.of(new AttributeOption("a", LocalizedText.of("en", "A")), new AttributeOption("a", LocalizedText.of("en", "A2"))), null))
        .isInstanceOf(DomainValidationException.class);
  }

  @Test
  void constraintsMustFitTheType() {
    assertThatThrownBy(() -> define("flag", AttributeType.BOOLEAN, null, new AttributeConstraints(null, 10, null, null, null, null)))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> define("weight", AttributeType.MEASUREMENT, null, null)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> define("size", AttributeType.DECIMAL, null, new AttributeConstraints(null, null, BigDecimal.TEN, BigDecimal.ONE, null, null)))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> define("Bad", AttributeType.STRING, null, null)).isInstanceOf(DomainValidationException.class);
  }
}
