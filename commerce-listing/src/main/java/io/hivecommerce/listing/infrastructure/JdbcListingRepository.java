package io.hivecommerce.listing.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.listing.domain.Listing;
import io.hivecommerce.listing.domain.ListingRepository;
import io.hivecommerce.platform.events.OutboxSource;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.CorrelationId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcListingRepository implements ListingRepository {
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcListingRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void insert(Listing l) {
    try {
      jdbc.sql("""
          insert into listing.listing(id, tenant_id, store_id, product_id, channel, submitted_by_seller, status, rejection_reason)
          values (:id, :tenant, :store, :product, :channel, :seller, :status, :reason)""")
          .param("id", l.id()).param("tenant", l.scope().tenantId()).param("store", l.scope().storeId()).param("product", l.productId())
          .param("channel", l.channel()).param("seller", l.submittedBySeller()).param("status", l.status().name()).param("reason", l.rejectionReason()).update();
    } catch (DuplicateKeyException e) {
      throw new DomainRuleException("LISTING_EXISTS", "The product already has a listing in this channel");
    }
  }

  @Override
  public void update(Listing l) {
    int updated = jdbc.sql("""
        update listing.listing set status = :status, rejection_reason = :reason, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("status", l.status().name()).param("reason", l.rejectionReason()).param("id", l.id()).param("tenant", l.scope().tenantId())
        .param("store", l.scope().storeId()).param("revision", l.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("listing " + l.id() + " changed concurrently");
  }

  @Override
  public Optional<Listing> findById(TenantScope scope, UUID id) {
    return select(scope, "id = :a", id, null).stream().findFirst();
  }

  @Override
  public Optional<Listing> findByProduct(TenantScope scope, UUID productId, String channel) {
    return select(scope, "product_id = :a and channel = :b", productId, channel).stream().findFirst();
  }

  @Override
  public List<Listing> findByStatus(TenantScope scope, Listing.Status status) { return select(scope, "status = :a", status.name(), null); }

  @Override
  public List<Listing> findBySeller(TenantScope scope, UUID sellerId) { return select(scope, "submitted_by_seller = :a", sellerId, null); }

  private List<Listing> select(TenantScope scope, String condition, Object a, Object b) {
    var statement = jdbc.sql("select id, product_id, channel, submitted_by_seller, status, rejection_reason, revision from listing.listing "
            + "where tenant_id = :tenant and store_id = :store and " + condition)
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("a", a);
    if (b != null) statement = statement.param("b", b);
    return statement.query((rs, n) -> Listing.restore(rs.getObject("id", UUID.class), scope, rs.getObject("product_id", UUID.class), rs.getString("channel"),
            rs.getObject("submitted_by_seller", UUID.class), Listing.Status.valueOf(rs.getString("status")), rs.getString("rejection_reason"), rs.getLong("revision")))
        .list();
  }

  @Override
  public void appendEvents(TenantScope scope, List<DomainEvent> events) {
    for (DomainEvent e : events) {
      try {
        jdbc.sql("""
            insert into listing.outbox_event(id, tenant_id, store_id, aggregate_type, aggregate_id, event_type, event_version, payload, correlation_id, occurred_at)
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
  static class OutboxRegistration {
    @Bean
    OutboxSource listingOutbox() { return new OutboxSource("listing"); }
  }
}
