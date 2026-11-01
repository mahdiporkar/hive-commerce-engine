package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.Violations;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One version of a product type's schema. DRAFT versions may be replaced; PUBLISHED versions are immutable. */
public record SchemaVersion(int number, Status status, List<AttributeSpec> specs, Instant publishedAt) {
  public enum Status { DRAFT, PUBLISHED }

  public SchemaVersion {
    specs = List.copyOf(specs);
  }

  SchemaVersion published(Instant now) { return new SchemaVersion(number, Status.PUBLISHED, specs, now); }

  public boolean hasVariantDefiningAttributes() { return specs.stream().anyMatch(AttributeSpec::variantDefining); }

  /** Validates and normalizes product-level values (all non-variant-defining attributes). */
  public Map<String, Object> normalizeProductAttributes(Map<String, Object> values, Violations v) {
    return normalize(values, false, "attributes", v);
  }

  /** Validates and normalizes one variant's values (exactly the variant-defining attributes). */
  public Map<String, Object> normalizeVariantAttributes(Map<String, Object> values, String field, Violations v) {
    return normalize(values, true, field, v);
  }

  private Map<String, Object> normalize(Map<String, Object> values, boolean variantLevel, String field, Violations v) {
    values = values == null ? Map.of() : values;
    var result = new TreeMap<String, Object>();
    var known = new LinkedHashMap<String, AttributeSpec>();
    specs.stream().filter(s -> s.variantDefining() == variantLevel).forEach(s -> known.put(s.key(), s));
    for (String key : values.keySet()) {
      if (!known.containsKey(key)) v.add(field + "." + key, variantLevel ? "is not a variant-defining attribute of this product type" : "is not a product attribute of this product type");
    }
    for (var spec : known.values()) {
      Object raw = values.get(spec.key());
      if (raw == null) {
        if (spec.required() || spec.variantDefining()) v.add(field + "." + spec.key(), "is required");
        continue;
      }
      Object normalized = spec.rule().normalize(raw, field + "." + spec.key(), v);
      if (normalized != null) result.put(spec.key(), normalized);
    }
    return result;
  }
}
