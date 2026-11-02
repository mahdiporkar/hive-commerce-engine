package io.hivecommerce.fulfillment.infrastructure;

import io.hivecommerce.fulfillment.domain.Fulfillment;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFulfillmentRepository {
  private final JdbcClient jdbc;

  JdbcFulfillmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertIfAbsent(Fulfillment f) {
    jdbc.sql("""
        insert into fulfillment.fulfillment(id, tenant_id, store_id, order_id, seller_order_id, seller_id, status)
        values (:id, :tenant, :store, :order, :so, :seller, :status) on conflict (tenant_id, store_id, seller_order_id) do nothing""")
        .param("id", f.id()).param("tenant", f.scope().tenantId()).param("store", f.scope().storeId()).param("order", f.orderId())
        .param("so", f.sellerOrderId()).param("seller", f.sellerId()).param("status", f.status().name()).update();
  }

  public void update(Fulfillment f) {
    int updated = jdbc.sql("""
        update fulfillment.fulfillment set status = :status, carrier = :carrier, tracking_code = :tracking, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("status", f.status().name()).param("carrier", f.carrier()).param("tracking", f.trackingCode()).param("id", f.id())
        .param("tenant", f.scope().tenantId()).param("store", f.scope().storeId()).param("revision", f.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("fulfillment " + f.id() + " changed concurrently");
  }

  public Optional<Fulfillment> findById(TenantScope scope, UUID id) { return select(scope, "id = :value", id).stream().findFirst(); }

  public List<Fulfillment> findByOrder(TenantScope scope, UUID orderId) { return select(scope, "order_id = :value", orderId); }

  public List<Fulfillment> findBySeller(TenantScope scope, UUID sellerId) { return select(scope, "seller_id = :value", sellerId); }

  private List<Fulfillment> select(TenantScope scope, String condition, UUID value) {
    return jdbc.sql("select id, order_id, seller_order_id, seller_id, status, carrier, tracking_code, revision from fulfillment.fulfillment "
            + "where tenant_id = :tenant and store_id = :store and " + condition + " order by created_at")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
        .query((rs, n) -> Fulfillment.restore(rs.getObject("id", UUID.class), scope, rs.getObject("order_id", UUID.class), rs.getObject("seller_order_id", UUID.class),
            rs.getObject("seller_id", UUID.class), Fulfillment.Status.valueOf(rs.getString("status")), rs.getString("carrier"), rs.getString("tracking_code"),
            rs.getLong("revision")))
        .list();
  }
}
