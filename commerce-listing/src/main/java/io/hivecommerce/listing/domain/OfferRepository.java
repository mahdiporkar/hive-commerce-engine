package io.hivecommerce.listing.domain;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository {
  void insert(SellerOffer offer);

  void update(SellerOffer offer);

  Optional<SellerOffer> findById(TenantScope scope, UUID id);

  List<SellerOffer> findActiveByProduct(TenantScope scope, UUID productId);

  List<SellerOffer> findBySeller(TenantScope scope, UUID sellerId);
}
