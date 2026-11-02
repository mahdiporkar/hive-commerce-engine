package io.hivecommerce.platform.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox events to in-process {@link EventHandler}s (at least once, in occurrence order per schema). Each
 * handler runs in its own scoped transaction that also records {@code platform.processed_event}, so a repeated delivery
 * is a no-op for consumers that already succeeded. An event is marked published only after every subscriber has it.
 * Runs in the deployment's scope (ADR-0009: background jobs set their scope explicitly; one store per deployment).
 */
@Component
public class OutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final List<OutboxSource> sources;
  private final List<EventHandler> handlers;
  private final TenantTransactions transactions;
  private final DeploymentScope deployment;
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final boolean enabled;

  public OutboxDispatcher(List<OutboxSource> sources, List<EventHandler> handlers, TenantTransactions transactions, DeploymentScope deployment,
      JdbcClient jdbc, ObjectMapper json, @Value("${commerce.events.dispatch-enabled:true}") boolean enabled) {
    this.sources = sources;
    this.handlers = handlers;
    this.transactions = transactions;
    this.deployment = deployment;
    this.jdbc = jdbc;
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${commerce.events.dispatch-interval-ms:300}", initialDelayString = "${commerce.events.dispatch-initial-delay-ms:1000}")
  public void dispatchScheduled() {
    if (enabled) dispatchAll();
  }

  /** Dispatches until every outbox is drained or no progress is possible; returns the number of events published. */
  public synchronized int dispatchAll() {
    int total = 0, round;
    do {
      round = 0;
      for (OutboxSource source : sources) round += dispatch(source);
      total += round;
    } while (round > 0);
    return total;
  }

  private int dispatch(OutboxSource source) {
    List<EventEnvelope> batch = transactions.inScope(deployment.scope(), () -> jdbc.sql("""
        select id, event_type, event_version, tenant_id, store_id, aggregate_type, aggregate_id, payload, correlation_id, occurred_at
        from %s.outbox_event where published_at is null order by occurred_at, id limit 100""".formatted(source.schema()))
        .query((rs, n) -> new EventEnvelope(rs.getObject("id", UUID.class), source.schema(), rs.getString("event_type"), rs.getInt("event_version"),
            rs.getString("tenant_id"), rs.getObject("store_id", UUID.class), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
            read(rs.getString("payload")), rs.getString("correlation_id"), rs.getTimestamp("occurred_at").toInstant()))
        .list());
    int published = 0;
    for (EventEnvelope event : batch) {
      if (!deliver(event)) break; // keep order: stop this schema at the first failure and retry later
      transactions.inScope(deployment.scope(), () -> jdbc.sql("update %s.outbox_event set published_at = now() where id = :id".formatted(source.schema()))
          .param("id", event.eventId()).update());
      published++;
    }
    return published;
  }

  private boolean deliver(EventEnvelope event) {
    for (EventHandler handler : handlers) {
      if (!handler.eventTypes().contains(event.type())) continue;
      if (event.correlationId() != null) MDC.put("correlationId", event.correlationId());
      try {
        transactions.inScope(deployment.scope(), () -> {
          int fresh = jdbc.sql("insert into platform.processed_event(consumer, event_id) values (:consumer, :id) on conflict do nothing")
              .param("consumer", handler.consumer()).param("id", event.eventId()).update();
          if (fresh == 1) handler.handle(event);
          return null;
        });
      } catch (RuntimeException e) {
        log.warn("Event {} ({}) failed in consumer {}; will retry", event.eventId(), event.type(), handler.consumer(), e);
        return false;
      } finally {
        MDC.remove("correlationId");
      }
    }
    return true;
  }

  private Map<String, Object> read(String payload) {
    try {
      return json.readValue(payload, MAP);
    } catch (Exception e) {
      throw new IllegalStateException("Corrupt outbox payload", e);
    }
  }
}
