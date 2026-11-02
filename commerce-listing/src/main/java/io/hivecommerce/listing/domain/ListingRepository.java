package io.hivecommerce.listing.domain;

import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRepository {
  void insert(Listing listing);

  void update(Listing listing);

  Optional<Listing> findById(TenantScope scope, UUID id);

  Optional<Listing> findByProduct(TenantScope scope, UUID productId, String channel);

  void appendEvents(TenantScope scope, List<DomainEvent> events);
}
