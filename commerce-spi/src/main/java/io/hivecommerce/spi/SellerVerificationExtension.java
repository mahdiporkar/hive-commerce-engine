package io.hivecommerce.spi;

import java.util.List;
import java.util.UUID;

/** Extra checks before a seller is verified. */
public interface SellerVerificationExtension extends CommerceExtension {
  List<Issue> reviewForVerification(UUID sellerId, ExtensionData data);
}
