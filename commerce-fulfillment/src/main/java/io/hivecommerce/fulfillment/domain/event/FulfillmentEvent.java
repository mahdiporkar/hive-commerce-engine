package io.hivecommerce.fulfillment.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the fulfillment context with a plain-data payload (docs/domain/domain-events.md). */
public record FulfillmentEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public FulfillmentEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.fulfillment." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Fulfillment"; }
}
