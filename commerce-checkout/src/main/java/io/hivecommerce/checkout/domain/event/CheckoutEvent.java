package io.hivecommerce.checkout.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the checkout context with a plain-data payload (docs/domain/domain-events.md). */
public record CheckoutEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public CheckoutEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.checkout." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "CheckoutAttempt"; }
}
