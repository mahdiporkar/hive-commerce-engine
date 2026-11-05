package io.hivecommerce.digital.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the digital context with a plain-data payload (docs/domain/domain-events.md). */
public record DigitalEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public DigitalEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.digital." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "DigitalAsset"; }
}
