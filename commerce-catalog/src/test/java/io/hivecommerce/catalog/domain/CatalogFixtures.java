package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Generic test vocabulary (no industry rules): a material enum, a weight measurement, a color and a note. */
final class CatalogFixtures {
  static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

  static final AttributeRule MATERIAL = new AttributeRule("material", AttributeType.ENUM, List.of("wood", "clay"), null);
  static final AttributeRule COLOR = new AttributeRule("color", AttributeType.COLOR, List.of("red", "blue"), null);
  static final AttributeRule WEIGHT = new AttributeRule("weight", AttributeType.MEASUREMENT, null,
      new AttributeConstraints(null, null, BigDecimal.ZERO, null, List.of("g", "kg"), null));
  static final AttributeRule NOTE = new AttributeRule("note", AttributeType.STRING, null, new AttributeConstraints(null, 20, null, null, null, null));

  static ProductType publishedType(List<AttributeSpec> specs) {
    var type = ProductType.create(Ids.newId(), SCOPE, "generic-physical", LocalizedText.of("en", "Generic"), specs);
    type.publish(1, NOW);
    return type;
  }

  private CatalogFixtures() {}
}
