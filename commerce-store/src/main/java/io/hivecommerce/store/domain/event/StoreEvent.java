package io.hivecommerce.store.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the store context with a plain-data payload (docs/domain/domain-events.md). */
public record StoreEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public StoreEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.store." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Store"; }
}
