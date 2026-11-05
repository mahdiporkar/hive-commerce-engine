package io.hivecommerce.spi;

import java.util.List;
import java.util.UUID;

/** Extra checks before a product is published (by moderation or by an operator). */
public interface ListingApprovalExtension extends CommerceExtension {
  List<Issue> reviewForPublication(UUID productId, ProductFacts product, ExtensionData data);
}
