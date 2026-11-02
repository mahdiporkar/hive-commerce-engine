package io.hivecommerce.catalog.domain.event;

import io.hivecommerce.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Any structural change of a category (created, moved, attributes assigned, archived). */
public record CategoryChanged(UUID categoryId, String change, Instant occurredAt) implements DomainEvent {
  @Override public String type() { return "commerce.catalog.CategoryChanged"; }
  @Override public int version() { return 1; }
  @Override public String aggregateType() { return "Category"; }
  @Override public String aggregateId() { return categoryId.toString(); }
  @Override public Map<String, Object> payload() { return Map.of("categoryId", categoryId.toString(), "change", change); }
}
