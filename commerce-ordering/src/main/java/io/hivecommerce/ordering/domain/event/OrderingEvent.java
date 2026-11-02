package io.hivecommerce.ordering.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the ordering context with a plain-data payload (docs/domain/domain-events.md). */
public record OrderingEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public OrderingEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.ordering." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Order"; }
}
