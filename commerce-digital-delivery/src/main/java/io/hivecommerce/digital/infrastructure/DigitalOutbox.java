package io.hivecommerce.digital.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.platform.events.OutboxSource;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.CorrelationId;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Transactional outbox of the digital context (ADR-0002): appended in the same transaction as the aggregate. */
@Component
public class DigitalOutbox {
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  DigitalOutbox(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void append(TenantScope scope, List<DomainEvent> events) {
    for (DomainEvent e : events) {
      try {
        jdbc.sql("""
            insert into digital.outbox_event(id, tenant_id, store_id, aggregate_type, aggregate_id, event_type, event_version, payload, correlation_id, occurred_at)
            values (:id, :tenant, :store, :aggregateType, :aggregateId, :type, :version, cast(:payload as jsonb), :correlation, :occurred)""")
            .param("id", Ids.newId()).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("aggregateType", e.aggregateType())
            .param("aggregateId", e.aggregateId()).param("type", e.type()).param("version", e.version()).param("payload", json.writeValueAsString(e.payload()))
            .param("correlation", CorrelationId.current()).param("occurred", Timestamp.from(e.occurredAt())).update();
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  @Configuration
  static class Registration {
    @Bean
    OutboxSource digitalOutboxSource() { return new OutboxSource("digital"); }
  }
}
