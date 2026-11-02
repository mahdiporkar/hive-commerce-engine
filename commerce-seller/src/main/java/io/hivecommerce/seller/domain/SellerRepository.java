package io.hivecommerce.seller.domain;

import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerRepository {
  void insertIfAbsent(Seller seller);

  void insert(Seller seller);

  /** Persists status and memberships; fails on a stale revision. */
  void update(Seller seller);

  Optional<Seller> findById(TenantScope scope, UUID id);

  List<Seller> findByMember(TenantScope scope, UUID actorId);

  List<Seller> findByStatus(TenantScope scope, Seller.Status status);

  void appendEvents(TenantScope scope, List<DomainEvent> events);
}
