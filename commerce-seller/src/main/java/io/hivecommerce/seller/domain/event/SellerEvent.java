package io.hivecommerce.seller.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Seller lifecycle facts: SellerApplied, SellerVerified, SellerApproved, SellerSuspended, SellerReinstated, Membership*. */
public record SellerEvent(UUID sellerId, String name, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.seller." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Seller"; }
  @Override public String aggregateId() { return sellerId.toString(); }
  @Override public Map<String, Object> payload() { return Map.of("sellerId", sellerId.toString()); }
}
