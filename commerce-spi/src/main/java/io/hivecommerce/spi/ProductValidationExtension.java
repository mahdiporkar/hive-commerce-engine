package io.hivecommerce.spi;

import java.util.List;

/** Extra validation beyond the product-type schema, on product creation. */
public interface ProductValidationExtension extends CommerceExtension {
  List<Issue> validate(ProductFacts product);
}
