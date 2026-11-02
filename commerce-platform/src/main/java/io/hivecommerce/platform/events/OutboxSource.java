package io.hivecommerce.platform.events;

import java.util.regex.Pattern;

/** Declares that a context schema has an {@code outbox_event} table to dispatch (ADR-0002: one outbox per schema). */
public record OutboxSource(String schema) {
  private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z_]{1,30}");

  public OutboxSource {
    if (schema == null || !SCHEMA.matcher(schema).matches()) throw new IllegalArgumentException("invalid outbox schema");
  }
}
