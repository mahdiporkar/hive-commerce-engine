package io.hivecommerce.catalog.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProductCreated(UUID productId, String productTypeKey, int schemaVersion, int variantCount, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.catalog.ProductCreated"; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Product"; }
  @Override public String aggregateId() { return productId.toString(); }
  @Override public Map<String, Object> payload() {
    return Map.of("productId", productId.toString(), "productTypeKey", productTypeKey, "schemaVersion", schemaVersion, "variantCount", variantCount);
  }
}
