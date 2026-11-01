package io.hivecommerce.catalog.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.catalog.domain.AttributeConstraints;
import io.hivecommerce.catalog.domain.AttributeFlags;
import io.hivecommerce.catalog.domain.AttributeOption;
import io.hivecommerce.catalog.domain.AttributeRule;
import io.hivecommerce.catalog.domain.AttributeSpec;
import io.hivecommerce.catalog.domain.AttributeType;
import io.hivecommerce.shared.LocalizedText;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JSONB mapping for Catalog columns. Kept in infrastructure so domain classes carry no serialization annotations.
 * Numbers are read as BigDecimal so stored attribute values keep their exact decimal form.
 */
@Component
class CatalogJson {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};
  private final ObjectMapper mapper;

  CatalogJson(ObjectMapper mapper) {
    this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize catalog value", e);
    }
  }

  Map<String, Object> map(String json) { return read(json, MAP); }

  List<Map<String, Object>> list(String json) { return read(json, LIST); }

  private <T> T read(String json, TypeReference<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt catalog JSON column", e);
    }
  }

  @SuppressWarnings("unchecked")
  LocalizedText text(String json) {
    var raw = map(json);
    var values = new LinkedHashMap<String, String>();
    raw.forEach((k, v) -> values.put(k, (String) v));
    return new LocalizedText(values);
  }

  List<Map<String, Object>> options(List<AttributeOption> options) {
    return options.stream().map(o -> Map.<String, Object>of("code", o.code(), "labels", o.labels().values())).toList();
  }

  @SuppressWarnings("unchecked")
  List<AttributeOption> options(String json) {
    return list(json).stream().map(m -> new AttributeOption((String) m.get("code"), new LocalizedText((Map<String, String>) m.get("labels")))).toList();
  }

  Map<String, Object> constraints(AttributeConstraints c) {
    var m = new LinkedHashMap<String, Object>();
    if (c.minLength() != null) m.put("minLength", c.minLength());
    if (c.maxLength() != null) m.put("maxLength", c.maxLength());
    if (c.min() != null) m.put("min", c.min());
    if (c.max() != null) m.put("max", c.max());
    if (c.units() != null) m.put("units", c.units());
    if (c.referenceType() != null) m.put("referenceType", c.referenceType());
    return m;
  }

  @SuppressWarnings("unchecked")
  AttributeConstraints constraints(Map<String, Object> m) {
    return new AttributeConstraints(intOrNull(m.get("minLength")), intOrNull(m.get("maxLength")), (BigDecimal) decimalOrNull(m.get("min")),
        (BigDecimal) decimalOrNull(m.get("max")), (List<String>) m.get("units"), (String) m.get("referenceType"));
  }

  Map<String, Object> flags(AttributeFlags f) {
    return Map.of("filterable", f.filterable(), "searchable", f.searchable(), "sortable", f.sortable(), "comparable", f.comparable(), "visible", f.visible());
  }

  AttributeFlags flags(Map<String, Object> m) {
    return new AttributeFlags(bool(m, "filterable"), bool(m, "searchable"), bool(m, "sortable"), bool(m, "comparable"), bool(m, "visible"));
  }

  List<Map<String, Object>> specs(List<AttributeSpec> specs) {
    var result = new ArrayList<Map<String, Object>>();
    for (var s : specs) {
      var m = new LinkedHashMap<String, Object>();
      m.put("key", s.key());
      m.put("type", s.rule().type().name());
      m.put("options", s.rule().optionCodes());
      m.put("constraints", constraints(s.rule().constraints()));
      m.put("required", s.required());
      m.put("variantDefining", s.variantDefining());
      result.add(m);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  List<AttributeSpec> specs(String json) {
    return list(json).stream().map(m -> new AttributeSpec(
        new AttributeRule((String) m.get("key"), AttributeType.valueOf((String) m.get("type")), (List<String>) m.get("options"),
            constraints((Map<String, Object>) m.get("constraints"))),
        (Boolean) m.get("required"), (Boolean) m.get("variantDefining"))).toList();
  }

  private static Integer intOrNull(Object value) { return value == null ? null : ((Number) value).intValue(); }

  private static Object decimalOrNull(Object value) { return value == null ? null : new BigDecimal(value.toString()); }

  private static boolean bool(Map<String, Object> m, String key) { return Boolean.TRUE.equals(m.get(key)); }
}
