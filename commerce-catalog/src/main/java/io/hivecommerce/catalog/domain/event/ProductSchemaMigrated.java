package io.hivecommerce.catalog.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProductSchemaMigrated(UUID productId, String productTypeKey, int fromVersion, int toVersion, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.catalog.ProductSchemaMigrated"; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Product"; }
  @Override public String aggregateId() { return productId.toString(); }
  @Override public Map<String, Object> payload() {
    return Map.of("productId", productId.toString(), "productTypeKey", productTypeKey, "fromVersion", fromVersion, "toVersion", toVersion);
  }
}
