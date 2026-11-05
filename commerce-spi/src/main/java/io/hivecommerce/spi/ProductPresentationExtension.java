package io.hivecommerce.spi;

import java.util.Map;
import java.util.UUID;

/** Extra public data on a product page, returned under {@code extensions.<key>}. Must never contain paid content. */
public interface ProductPresentationExtension extends CommerceExtension {
  Map<String, Object> present(UUID productId, ProductFacts product, ExtensionData data);
}
