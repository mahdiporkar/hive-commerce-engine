package io.hivecommerce.catalog.domain;

/**
 * How a product type uses an attribute. Variant-defining attributes live on variants and are always required there;
 * all other attributes live on the product.
 */
public record AttributeSpec(AttributeRule rule, boolean required, boolean variantDefining) {
  public String key() { return rule.key(); }
}
