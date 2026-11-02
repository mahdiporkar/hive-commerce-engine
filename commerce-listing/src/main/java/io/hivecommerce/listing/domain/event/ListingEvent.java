package io.hivecommerce.listing.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** ApprovalRequested, ListingApproved, ListingRejected, ProductPublished, ProductUnpublished. */
public record ListingEvent(UUID listingId, UUID productId, String name, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.listing." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Listing"; }
  @Override public String aggregateId() { return listingId.toString(); }
  @Override public Map<String, Object> payload() { return Map.of("listingId", listingId.toString(), "productId", productId.toString()); }
}
