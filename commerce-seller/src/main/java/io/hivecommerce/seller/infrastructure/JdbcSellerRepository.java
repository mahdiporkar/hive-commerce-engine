package io.hivecommerce.seller.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.platform.events.OutboxSource;
import io.hivecommerce.seller.domain.Seller;
import io.hivecommerce.seller.domain.SellerRepository;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.CorrelationId;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerRepository implements SellerRepository {
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcSellerRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void insertIfAbsent(Seller s) {
    insert(s, true);
  }

  @Override
  public void insert(Seller s) {
    insert(s, false);
  }

  private void insert(Seller s, boolean ignoreExisting) {
    int inserted = jdbc.sql("insert into seller.seller(id, tenant_id, store_id, kind, status, display_name) values (:id, :tenant, :store, :kind, :status, cast(:name as jsonb))"
            + (ignoreExisting ? " on conflict (id) do nothing" : ""))
        .param("id", s.id()).param("tenant", s.scope().tenantId()).param("store", s.scope().storeId()).param("kind", s.kind().name())
        .param("status", s.status().name()).param("name", write(s.displayName().values())).update();
    if (inserted == 1) writeMemberships(s);
  }

  @Override
  public void update(Seller s) {
    int updated = jdbc.sql("""
        update seller.seller set status = :status, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("status", s.status().name()).param("id", s.id()).param("tenant", s.scope().tenantId()).param("store", s.scope().storeId())
        .param("revision", s.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("seller " + s.id() + " changed concurrently");
    writeMemberships(s);
  }

  private void writeMemberships(Seller s) {
    for (var m : membershipsIncludingInactive(s)) {
      jdbc.sql("""
          insert into seller.seller_membership(seller_id, tenant_id, store_id, actor_id, role, capabilities, status)
          values (:seller, :tenant, :store, :actor, :role, :caps, :status)
          on conflict (seller_id, actor_id) do update set role = excluded.role, capabilities = excluded.capabilities, status = excluded.status, updated_at = now()""")
          .param("seller", s.id()).param("tenant", s.scope().tenantId()).param("store", s.scope().storeId()).param("actor", m.actorId())
          .param("role", m.role().name()).param("caps", m.capabilities().stream().sorted().toArray(String[]::new)).param("status", m.status().name()).update();
    }
  }

  /** The aggregate exposes memberships; revoked ones are kept as rows (history), never deleted. */
  private static List<Seller.Membership> membershipsIncludingInactive(Seller s) { return s.memberships(); }

  @Override
  public Optional<Seller> findById(TenantScope scope, UUID id) {
    return select(scope, "s.id = :value", id).stream().findFirst();
  }

  @Override
  public List<Seller> findByMember(TenantScope scope, UUID actorId) {
    return select(scope, "s.id in (select seller_id from seller.seller_membership where actor_id = :value and status = 'ACTIVE')", actorId);
  }

  @Override
  public List<Seller> findByStatus(TenantScope scope, Seller.Status status) {
    return select(scope, "s.status = :value", status.name());
  }

  private List<Seller> select(TenantScope scope, String condition, Object value) {
    var rows = jdbc.sql("select s.id, s.kind, s.status, s.display_name, s.revision from seller.seller s where s.tenant_id = :tenant and s.store_id = :store and "
            + condition + " order by s.created_at")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
        .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"), rs.getString("display_name"), rs.getLong("revision")})
        .list();
    return rows.stream().map(r -> Seller.restore((UUID) r[0], scope, Seller.Kind.valueOf((String) r[1]), new LocalizedText(read((String) r[3])),
        Seller.Status.valueOf((String) r[2]), memberships(scope, (UUID) r[0]), (Long) r[4])).toList();
  }

  private List<Seller.Membership> memberships(TenantScope scope, UUID sellerId) {
    return jdbc.sql("select actor_id, role, capabilities, status from seller.seller_membership where seller_id = :seller and tenant_id = :tenant and store_id = :store")
        .param("seller", sellerId).param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new Seller.Membership(rs.getObject("actor_id", UUID.class), Seller.Role.valueOf(rs.getString("role")),
            new HashSet<>(Arrays.asList((String[]) rs.getArray("capabilities").getArray())), Seller.MembershipStatus.valueOf(rs.getString("status"))))
        .list();
  }

  @Override
  public void appendEvents(TenantScope scope, List<DomainEvent> events) {
    for (DomainEvent e : events) {
      jdbc.sql("""
          insert into seller.outbox_event(id, tenant_id, store_id, aggregate_type, aggregate_id, event_type, event_version, payload, correlation_id, occurred_at)
          values (:id, :tenant, :store, :aggregateType, :aggregateId, :type, :version, cast(:payload as jsonb), :correlation, :occurred)""")
          .param("id", Ids.newId()).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("aggregateType", e.aggregateType())
          .param("aggregateId", e.aggregateId()).param("type", e.type()).param("version", e.version()).param("payload", write(e.payload()))
          .param("correlation", CorrelationId.current()).param("occurred", Timestamp.from(e.occurredAt())).update();
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, String> read(String value) {
    try {
      return json.readValue(value, new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Configuration
  static class OutboxRegistration {
    @Bean
    OutboxSource sellerOutbox() { return new OutboxSource("seller"); }
  }
}
