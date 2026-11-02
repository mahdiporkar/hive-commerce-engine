package io.hivecommerce.listing.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** OfferCreated, OfferPriceChanged, OfferWithdrawn. */
public record OfferEvent(String name, UUID offerId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public OfferEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.listing." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "SellerOffer"; }
  @Override public String aggregateId() { return offerId.toString(); }
}
