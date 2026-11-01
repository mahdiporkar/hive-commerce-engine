package io.hivecommerce.catalog.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AttributeDefined(UUID attributeId, String key, String attributeType, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.catalog.AttributeDefined"; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "AttributeDefinition"; }
  @Override public String aggregateId() { return attributeId.toString(); }
  @Override public Map<String, Object> payload() { return Map.of("attributeId", attributeId.toString(), "key", key, "type", attributeType); }
}
