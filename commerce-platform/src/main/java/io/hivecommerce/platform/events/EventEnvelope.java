package io.hivecommerce.platform.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An outbox row as delivered to consumers (docs/domain/domain-events.md). Consumers dedupe on {@link #eventId}. */
public record EventEnvelope(UUID eventId, String sourceSchema, String type, int version, String tenantId, UUID storeId, String aggregateType,
    String aggregateId, Map<String, Object> payload, String correlationId, Instant occurredAt) {}
