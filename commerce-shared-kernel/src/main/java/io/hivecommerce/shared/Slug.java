package io.hivecommerce.shared;

import java.util.regex.Pattern;

/** URL path segment: lowercase ASCII letters, digits and single hyphens. Localized slugs are a later concern. */
public record Slug(String value) {
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  public Slug {
    if (value == null || value.length() > 120 || !SLUG.matcher(value).matches()) {
      throw DomainValidationException.of("slug", "must be lowercase letters, digits and single hyphens (max 120)");
    }
  }
}
