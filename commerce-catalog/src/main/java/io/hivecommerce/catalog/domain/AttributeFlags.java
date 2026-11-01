package io.hivecommerce.catalog.domain;

/** Presentation and search behavior (§9 attribute metadata). Used by the search projection and generated forms. */
public record AttributeFlags(boolean filterable, boolean searchable, boolean sortable, boolean comparable, boolean visible) {
  public static final AttributeFlags DEFAULT = new AttributeFlags(false, false, false, false, true);
}
