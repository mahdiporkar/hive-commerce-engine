package io.hivecommerce.platform.events;

import java.util.Set;

/**
 * An in-process consumer of domain events from other contexts. Delivery is at least once; the dispatcher records
 * {@code (consumer, eventId)} in the same transaction as {@link #handle}, so a handler runs at most once per event
 * per consumer even when delivery repeats.
 */
public interface EventHandler {
  /** Stable consumer name, for example {@code ordering.payment-captured}. */
  String consumer();

  Set<String> eventTypes();

  /** Runs inside a tenant-scoped transaction for the event's tenant and store. */
  void handle(EventEnvelope event);
}
