package io.hivecommerce.catalog.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProductTypeSchemaPublished(UUID productTypeId, String key, int schemaVersion, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.catalog.ProductTypeSchemaPublished"; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "ProductType"; }
  @Override public String aggregateId() { return productTypeId.toString(); }
  @Override public Map<String, Object> payload() { return Map.of("productTypeId", productTypeId.toString(), "key", key, "schemaVersion", schemaVersion); }
}
