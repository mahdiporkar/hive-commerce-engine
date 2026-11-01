package io.hivecommerce.shared;

import java.time.Instant;
import java.util.Map;

/**
 * A fact that happened inside one aggregate (docs/domain/domain-events.md). The payload is plain data
 * (strings, numbers, booleans, lists, maps) so the domain needs no serialization library.
 */
public interface DomainEvent {
  /** {@code commerce.<context>.<EventName>} */
  String type();

  int version();

  String aggregateType();

  String aggregateId();

  Instant occurredAt();

  Map<String, Object> payload();
}
