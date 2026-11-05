package io.hivecommerce.seller.application;

import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.seller.domain.Seller;
import io.hivecommerce.seller.domain.SellerRepository;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class SellerDirectoryService implements SellerDirectory, io.hivecommerce.store.application.published.StoreDirectory.ExternalSellers {
  private final SellerRepository sellers;

  SellerDirectoryService(SellerRepository sellers) {
    this.sellers = sellers;
  }

  @Override
  public boolean anyActive(TenantScope scope) {
    return sellers.findByStatus(scope, Seller.Status.ACTIVE).stream().anyMatch(s -> s.kind() == Seller.Kind.EXTERNAL);
  }

  @Override
  public UUID internalSellerId(TenantScope scope) { return Seller.internalIdFor(scope); }

  @Override
  public Optional<SellerSummary> seller(TenantScope scope, UUID sellerId) {
    return sellers.findById(scope, sellerId).map(s -> new SellerSummary(s.id(), s.kind().name(), s.status().name(), s.displayName().values()));
  }

  @Override
  public boolean allows(TenantScope scope, UUID actorId, UUID sellerId, String capability) {
    return actorId != null && sellers.findById(scope, sellerId).map(s -> s.allows(actorId, capability)).orElse(false);
  }

  @Override
  public List<MembershipSummary> membershipsOf(TenantScope scope, UUID actorId) {
    return sellers.findByMember(scope, actorId).stream().filter(s -> s.status() == Seller.Status.ACTIVE)
        .flatMap(s -> s.membership(actorId).stream().map(m -> new MembershipSummary(s.id(), m.role().name(),
            m.role() == Seller.Role.OWNER ? List.copyOf(Seller.CAPABILITIES) : List.copyOf(m.capabilities()))))
        .toList();
  }
}
