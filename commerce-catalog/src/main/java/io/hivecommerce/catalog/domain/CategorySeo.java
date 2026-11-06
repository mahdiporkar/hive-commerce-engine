package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.Violations;
import java.util.Map;

/** Localized search metadata of a category. Absent values fall back to the category name. */
public record CategorySeo(Map<String, String> title, Map<String, String> description) {
  public static CategorySeo validated(CategorySeo seo) {
    var title = seo == null || seo.title() == null ? Map.<String, String>of() : seo.title();
    var description = seo == null || seo.description() == null ? Map.<String, String>of() : seo.description();
    var v = new Violations();
    title.forEach((locale, text) -> { if (text == null || text.length() > 70) v.add("title." + locale, "at most 70 characters"); });
    description.forEach((locale, text) -> { if (text == null || text.length() > 320) v.add("description." + locale, "at most 320 characters"); });
    v.throwIfAny();
    return new CategorySeo(title, description);
  }
}
