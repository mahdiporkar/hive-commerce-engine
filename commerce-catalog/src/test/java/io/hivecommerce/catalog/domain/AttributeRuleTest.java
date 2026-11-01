package io.hivecommerce.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.hivecommerce.shared.Violations;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AttributeRuleTest {
  private static AttributeRule rule(AttributeType type) { return new AttributeRule("a", type, List.of("x", "y"), null); }

  static List<Arguments> accepted() {
    return List.of(
        Arguments.of(AttributeType.STRING, "hello", "hello"),
        Arguments.of(AttributeType.INTEGER, 42, new BigDecimal("42")),
        Arguments.of(AttributeType.INTEGER, new BigDecimal("7.0"), new BigDecimal("7")),
        Arguments.of(AttributeType.DECIMAL, new BigDecimal("2.345"), new BigDecimal("2.345")),
        Arguments.of(AttributeType.BOOLEAN, true, true),
        Arguments.of(AttributeType.DATE, "2026-11-02", "2026-11-02"),
        Arguments.of(AttributeType.DATETIME, "2026-11-02T10:15:30+03:30", "2026-11-02T10:15:30+03:30"),
        Arguments.of(AttributeType.ENUM, "x", "x"),
        Arguments.of(AttributeType.MULTI_SELECT, List.of("x", "y"), List.of("x", "y")),
        Arguments.of(AttributeType.URL, "https://example.org/a", "https://example.org/a"),
        Arguments.of(AttributeType.REFERENCE, "cert:GIA-123", "cert:GIA-123"),
        Arguments.of(AttributeType.RANGE, Map.of("min", 1, "max", 3), Map.of("min", new BigDecimal("1"), "max", new BigDecimal("3"))),
        Arguments.of(AttributeType.JSON, Map.of("k", List.of(1, 2)), Map.of("k", List.of(1, 2))));
  }

  @ParameterizedTest(name = "{0} accepts {1}")
  @MethodSource("accepted")
  void normalizesValidValues(AttributeType type, Object raw, Object expected) {
    var violations = new Violations();
    assertThat(rule(type).normalize(raw, "a", violations)).isEqualTo(expected);
    assertThat(violations.isEmpty()).isTrue();
  }

  static List<Arguments> rejected() {
    return List.of(
        Arguments.of(AttributeType.STRING, 5),
        Arguments.of(AttributeType.INTEGER, 1.5),
        Arguments.of(AttributeType.INTEGER, "12"),
        Arguments.of(AttributeType.DECIMAL, Double.NaN),
        Arguments.of(AttributeType.BOOLEAN, "true"),
        Arguments.of(AttributeType.DATE, "02/11/2026"),
        Arguments.of(AttributeType.DATETIME, "2026-11-02T10:15:30"),
        Arguments.of(AttributeType.ENUM, "z"),
        Arguments.of(AttributeType.MULTI_SELECT, List.of("x", "x")),
        Arguments.of(AttributeType.MULTI_SELECT, List.of()),
        Arguments.of(AttributeType.URL, "javascript:alert(1)"),
        Arguments.of(AttributeType.URL, "https://user:pw@example.org"),
        Arguments.of(AttributeType.REFERENCE, "has space"),
        Arguments.of(AttributeType.RANGE, Map.of("min", 5, "max", 1)),
        Arguments.of(AttributeType.JSON, List.of(1)));
  }

  @ParameterizedTest(name = "{0} rejects {1}")
  @MethodSource("rejected")
  void rejectsInvalidValues(AttributeType type, Object raw) {
    var violations = new Violations();
    assertThat(rule(type).normalize(raw, "a", violations)).isNull();
    assertThat(violations.isEmpty()).isFalse();
  }

  @Test
  void measurementChecksUnitAndBounds() {
    var violations = new Violations();
    assertThat(CatalogFixtures.WEIGHT.normalize(Map.of("value", 12.5, "unit", "g"), "weight", violations))
        .isEqualTo(Map.of("value", new BigDecimal("12.5"), "unit", "g"));
    assertThat(CatalogFixtures.WEIGHT.normalize(Map.of("value", 1, "unit", "lb"), "weight", violations)).isNull();
    assertThat(CatalogFixtures.WEIGHT.normalize(Map.of("value", -1, "unit", "g"), "weight", violations)).isNull();
  }

  @Test
  void stringRespectsMaxLength() {
    var violations = new Violations();
    assertThat(CatalogFixtures.NOTE.normalize("x".repeat(21), "note", violations)).isNull();
  }

  @Test
  void colorWithoutOptionsAcceptsHex() {
    var free = new AttributeRule("c", AttributeType.COLOR, null, null);
    var violations = new Violations();
    assertThat(free.normalize("#a1b2c3", "c", violations)).isEqualTo("#A1B2C3");
    assertThat(free.normalize("red", "c", violations)).isNull();
  }
}
