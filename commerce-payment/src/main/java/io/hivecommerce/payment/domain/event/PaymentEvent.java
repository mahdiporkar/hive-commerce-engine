package io.hivecommerce.payment.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

/** A named fact of the payment context with a plain-data payload (docs/domain/domain-events.md). */
public record PaymentEvent(String name, String aggregateId, Map<String, Object> payload, Instant occurredAt) implements DomainEvent {
  public PaymentEvent {
    payload = Map.copyOf(payload);
  }

  @Override public String type() { return "commerce.payment." + name; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Payment"; }
}
