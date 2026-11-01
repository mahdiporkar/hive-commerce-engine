package io.hivecommerce.shared;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Text in one or more locales (fa, ar, en, …). At least one locale; each value non-blank and bounded. */
public record LocalizedText(Map<String, String> values) {
  private static final Pattern LOCALE = Pattern.compile("[a-z]{2}(-[A-Z]{2})?");
  public static final int MAX_LENGTH = 500;

  public LocalizedText {
    if (values == null || values.isEmpty()) throw DomainValidationException.of("", "at least one locale is required");
    var copy = new TreeMap<String, String>();
    for (var entry : values.entrySet()) {
      String locale = entry.getKey(), text = entry.getValue();
      if (locale == null || !LOCALE.matcher(locale).matches()) throw DomainValidationException.of(String.valueOf(locale), "invalid locale");
      if (text == null || text.isBlank()) throw DomainValidationException.of(locale, "text must not be blank");
      if (text.length() > MAX_LENGTH) throw DomainValidationException.of(locale, "text longer than " + MAX_LENGTH);
      copy.put(locale, text.strip());
    }
    values = Collections.unmodifiableMap(copy);
  }

  public static LocalizedText of(String locale, String text) { return new LocalizedText(Map.of(locale, text)); }
}
