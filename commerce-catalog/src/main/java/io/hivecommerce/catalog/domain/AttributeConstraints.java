package io.hivecommerce.catalog.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Optional validation limits. Which fields apply depends on the type: lengths for text, min/max for numbers and
 * ranges, units for measurements, referenceType for references. All fields may be null.
 */
public record AttributeConstraints(Integer minLength, Integer maxLength, BigDecimal min, BigDecimal max, List<String> units, String referenceType) {
  public static final AttributeConstraints NONE = new AttributeConstraints(null, null, null, null, null, null);

  public AttributeConstraints {
    units = units == null ? null : List.copyOf(units);
  }
}
