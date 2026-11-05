package io.hivecommerce.finance.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the finance context with a plain-data payload (docs/domain/domain-events.md). */
public record FinanceEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public FinanceEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.finance." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Finance"; }
}
