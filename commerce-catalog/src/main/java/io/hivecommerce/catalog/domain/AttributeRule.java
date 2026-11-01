package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.Violations;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validation-relevant snapshot of an attribute definition. A published schema version stores these snapshots,
 * so later edits to the attribute library never change what an existing schema accepts (ADR-0015 change 1).
 *
 * <p>Values arrive as plain data (String, Number, Boolean, List, Map). {@link #normalize} returns the canonical form
 * that is stored: numbers as BigDecimal, dates as ISO strings, option codes as strings.
 */
public record AttributeRule(String key, AttributeType type, List<String> optionCodes, AttributeConstraints constraints) {
  private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final int DEFAULT_STRING_MAX = 255, DEFAULT_LONG_TEXT_MAX = 20_000, JSON_MAX_KEYS = 50, JSON_MAX_DEPTH = 5;

  public AttributeRule {
    optionCodes = optionCodes == null ? List.of() : List.copyOf(optionCodes);
    constraints = constraints == null ? AttributeConstraints.NONE : constraints;
  }

  /** @return the canonical value, or null after recording a violation for {@code field} */
  public Object normalize(Object raw, String field, Violations violations) {
    if (raw == null) {
      violations.add(field, "must not be null");
      return null;
    }
    return switch (type) {
      case STRING -> text(raw, field, violations, DEFAULT_STRING_MAX);
      case LONG_TEXT -> text(raw, field, violations, DEFAULT_LONG_TEXT_MAX);
      case INTEGER -> integer(raw, field, violations);
      case DECIMAL -> bounded(decimal(raw), field, violations, "must be a number");
      case BOOLEAN -> raw instanceof Boolean b ? b : fail(field, violations, "must be true or false");
      case DATE -> parse(raw, field, violations, "must be an ISO date (yyyy-mm-dd)", s -> LocalDate.parse(s).toString());
      case DATETIME -> parse(raw, field, violations, "must be an ISO date-time with offset", s -> OffsetDateTime.parse(s).toString());
      case ENUM -> option(raw, field, violations);
      case MULTI_SELECT -> multiSelect(raw, field, violations);
      case COLOR -> color(raw, field, violations);
      case MEASUREMENT -> measurement(raw, field, violations);
      case RANGE -> range(raw, field, violations);
      case URL -> url(raw, field, violations);
      case REFERENCE -> raw instanceof String s && REFERENCE.matcher(s).matches() ? s : fail(field, violations, "must be a reference id");
      case JSON -> json(raw, field, violations);
    };
  }

  private Object text(Object raw, String field, Violations v, int defaultMax) {
    if (!(raw instanceof String s)) return fail(field, v, "must be text");
    int max = constraints.maxLength() != null ? constraints.maxLength() : defaultMax;
    int min = constraints.minLength() != null ? constraints.minLength() : 0;
    if (s.length() > max) return fail(field, v, "longer than " + max + " characters");
    if (s.length() < min) return fail(field, v, "shorter than " + min + " characters");
    return s;
  }

  private Object integer(Object raw, String field, Violations v) {
    BigDecimal value = decimal(raw);
    if (value == null) return fail(field, v, "must be a whole number");
    try {
      BigInteger exact = value.toBigIntegerExact();
      return bounded(new BigDecimal(exact), field, v, "must be a whole number");
    } catch (ArithmeticException e) {
      return fail(field, v, "must be a whole number");
    }
  }

  private Object bounded(BigDecimal value, String field, Violations v, String typeMessage) {
    if (value == null) return fail(field, v, typeMessage);
    if (constraints.min() != null && value.compareTo(constraints.min()) < 0) return fail(field, v, "must be at least " + constraints.min().toPlainString());
    if (constraints.max() != null && value.compareTo(constraints.max()) > 0) return fail(field, v, "must be at most " + constraints.max().toPlainString());
    return value;
  }

  private Object option(Object raw, String field, Violations v) {
    return raw instanceof String s && optionCodes.contains(s) ? s : fail(field, v, "must be one of " + optionCodes);
  }

  private Object multiSelect(Object raw, String field, Violations v) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) return fail(field, v, "must be a non-empty list of options");
    Set<String> seen = new HashSet<>();
    List<String> values = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String s) || !optionCodes.contains(s)) return fail(field, v, "every item must be one of " + optionCodes);
      if (!seen.add(s)) return fail(field, v, "must not repeat options");
      values.add(s);
    }
    return List.copyOf(values);
  }

  private Object color(Object raw, String field, Violations v) {
    if (!(raw instanceof String s)) return fail(field, v, "must be a color");
    if (!optionCodes.isEmpty()) return optionCodes.contains(s) ? s : fail(field, v, "must be one of " + optionCodes);
    return COLOR.matcher(s).matches() ? s.toUpperCase() : fail(field, v, "must be a hex color such as #A1B2C3");
  }

  private Object measurement(Object raw, String field, Violations v) {
    if (!(raw instanceof Map<?, ?> map) || map.size() != 2) return fail(field, v, "must be {value, unit}");
    BigDecimal value = decimal(map.get("value"));
    Object unit = map.get("unit");
    if (value == null) return fail(field + ".value", v, "must be a number");
    if (!(unit instanceof String u) || (constraints.units() != null && !constraints.units().contains(u))) {
      return fail(field + ".unit", v, "must be one of " + constraints.units());
    }
    if (bounded(value, field + ".value", v, "must be a number") == null) return null;
    var result = new LinkedHashMap<String, Object>();
    result.put("value", value);
    result.put("unit", u);
    return result;
  }

  private Object range(Object raw, String field, Violations v) {
    if (!(raw instanceof Map<?, ?> map) || map.size() != 2) return fail(field, v, "must be {min, max}");
    BigDecimal min = decimal(map.get("min")), max = decimal(map.get("max"));
    if (min == null || max == null) return fail(field, v, "min and max must be numbers");
    if (min.compareTo(max) > 0) return fail(field, v, "min must not exceed max");
    if (bounded(min, field + ".min", v, "must be a number") == null || bounded(max, field + ".max", v, "must be a number") == null) return null;
    var result = new LinkedHashMap<String, Object>();
    result.put("min", min);
    result.put("max", max);
    return result;
  }

  private Object url(Object raw, String field, Violations v) {
    if (!(raw instanceof String s) || s.length() > 2000) return fail(field, v, "must be an http(s) URL");
    try {
      URI uri = new URI(s);
      boolean ok = uri.isAbsolute() && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null;
      return ok ? s : fail(field, v, "must be an http(s) URL");
    } catch (Exception e) {
      return fail(field, v, "must be an http(s) URL");
    }
  }

  /** Structured JSON: an object, bounded in size and depth. Schema-level validation of its content is a later phase. */
  private Object json(Object raw, String field, Violations v) {
    if (!(raw instanceof Map<?, ?> map)) return fail(field, v, "must be a JSON object");
    if (map.size() > JSON_MAX_KEYS || depth(raw) > JSON_MAX_DEPTH) return fail(field, v, "is too large or too deeply nested");
    return raw;
  }

  private static int depth(Object value) {
    if (value instanceof Map<?, ?> map) return 1 + map.values().stream().mapToInt(AttributeRule::depth).max().orElse(0);
    if (value instanceof List<?> list) return 1 + list.stream().mapToInt(AttributeRule::depth).max().orElse(0);
    return 0;
  }

  private static Object parse(Object raw, String field, Violations v, String message, java.util.function.Function<String, String> parser) {
    if (!(raw instanceof String s)) return fail(field, v, message);
    try {
      return parser.apply(s);
    } catch (DateTimeParseException e) {
      return fail(field, v, message);
    }
  }

  private static BigDecimal decimal(Object raw) {
    if (raw instanceof BigDecimal d) return d;
    if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof BigInteger) return new BigDecimal(raw.toString());
    if (raw instanceof Double || raw instanceof Float) {
      double d = ((Number) raw).doubleValue();
      return Double.isFinite(d) ? new BigDecimal(raw.toString()) : null;
    }
    return null;
  }

  private static Object fail(String field, Violations v, String message) {
    v.add(field, message);
    return null;
  }
}
