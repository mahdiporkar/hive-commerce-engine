package io.hivecommerce.inventory.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the inventory context with a plain-data payload (docs/domain/domain-events.md). */
public record InventoryEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public InventoryEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.inventory." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "StockItem"; }
}
