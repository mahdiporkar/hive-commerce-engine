package io.hivecommerce.listing.application.published;

import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Listing's published API: what checkout needs to know about an offer at the moment of purchase. */
public interface ListingQueries {
  record OfferSnapshot(UUID offerId, UUID sellerId, UUID productId, UUID variantId, Money price, boolean uniqueItem, boolean digital, boolean purchasable,
      String sku, String productSlug, Map<String, String> productTitle, Map<String, Object> variantAttributes) {}

  /** The offer with its product snapshot; {@code purchasable} is false unless the offer is active and its listing published. */
  Optional<OfferSnapshot> offer(TenantScope scope, UUID offerId);
}
