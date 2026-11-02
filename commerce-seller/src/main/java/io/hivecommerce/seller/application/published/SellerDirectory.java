package io.hivecommerce.seller.application.published;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Seller's published in-process API. Callers must be inside a scoped transaction. */
public interface SellerDirectory {
  record SellerSummary(UUID sellerId, String kind, String status, java.util.Map<String, String> displayName) {}

  record MembershipSummary(UUID sellerId, String role, List<String> capabilities) {}

  UUID internalSellerId(TenantScope scope);

  Optional<SellerSummary> seller(TenantScope scope, UUID sellerId);

  /** Layer-2 relation check (ADR-0016): active member of an active seller holding the capability. */
  boolean allows(TenantScope scope, UUID actorId, UUID sellerId, String capability);

  List<MembershipSummary> membershipsOf(TenantScope scope, UUID actorId);
}
