package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.Violations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public images and videos of a product, by https link, in display order. Paid content never belongs here. */
public record ProductMedia(String url, String kind, Map<String, String> alt) {
  public static final int MAX = 20;
  private static final Set<String> KINDS = Set.of("IMAGE", "VIDEO");

  public static List<ProductMedia> validated(List<ProductMedia> media) {
    var v = new Violations();
    var items = media == null ? List.<ProductMedia>of() : media;
    if (items.size() > MAX) v.add("media", "at most " + MAX);
    var result = new ArrayList<ProductMedia>();
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      String field = "media[" + i + "]";
      if (item == null || item.url() == null || !item.url().startsWith("https://") || item.url().length() > 1000) {
        v.add(field + ".url", "must be an https link of at most 1000 characters");
        continue;
      }
      String kind = item.kind() == null ? "IMAGE" : item.kind();
      if (!KINDS.contains(kind)) v.add(field + ".kind", "must be IMAGE or VIDEO");
      result.add(new ProductMedia(item.url(), kind, item.alt() == null ? Map.of() : item.alt()));
    }
    v.throwIfAny();
    return result;
  }
}
