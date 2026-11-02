package io.hivecommerce.inventory.infrastructure;

import io.hivecommerce.inventory.domain.StockItem;
import io.hivecommerce.inventory.domain.StockRepository;
import io.hivecommerce.shared.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStockRepository implements StockRepository {
  private final JdbcClient jdbc;

  JdbcStockRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertIfAbsent(StockItem i) {
    jdbc.sql("""
        insert into inventory.stock_item(id, tenant_id, store_id, offer_id, seller_id, unique_item, unlimited, on_hand, reserved)
        values (:id, :tenant, :store, :offer, :seller, :unique, :unlimited, :onHand, 0) on conflict (tenant_id, store_id, offer_id) do nothing""")
        .param("id", i.id()).param("tenant", i.scope().tenantId()).param("store", i.scope().storeId()).param("offer", i.offerId())
        .param("seller", i.sellerId()).param("unique", i.unique()).param("unlimited", i.unlimited()).param("onHand", i.onHand()).update();
  }

  @Override
  public void update(StockItem i) {
    int updated = jdbc.sql("""
        update inventory.stock_item set on_hand = :onHand, reserved = :reserved, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("onHand", i.onHand()).param("reserved", i.reserved()).param("id", i.id()).param("tenant", i.scope().tenantId())
        .param("store", i.scope().storeId()).param("revision", i.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("stock item " + i.id() + " changed concurrently");
    for (var r : i.reservations()) {
      jdbc.sql("""
          insert into inventory.reservation(id, tenant_id, store_id, stock_item_id, checkout_attempt_id, quantity, expires_at, status)
          values (:id, :tenant, :store, :item, :attempt, :quantity, :expires, :status)
          on conflict (id) do update set status = excluded.status, updated_at = now() where inventory.reservation.status = 'RESERVED'""")
          .param("id", r.id()).param("tenant", i.scope().tenantId()).param("store", i.scope().storeId()).param("item", i.id())
          .param("attempt", r.checkoutAttemptId()).param("quantity", r.quantity()).param("expires", Timestamp.from(r.expiresAt()))
          .param("status", r.status().name()).update();
    }
  }

  @Override
  public Optional<StockItem> findByOffer(TenantScope scope, UUID offerId) {
    return select(scope, "offer_id = :value", offerId).stream().findFirst();
  }

  @Override
  public List<StockItem> findByOffers(TenantScope scope, Collection<UUID> offerIds) {
    return offerIds.isEmpty() ? List.of() : select(scope, "offer_id in (:value)", offerIds);
  }

  @Override
  public List<StockItem> findByAttempt(TenantScope scope, UUID attemptId) {
    return select(scope, "id in (select stock_item_id from inventory.reservation where checkout_attempt_id = :value)", attemptId);
  }

  @Override
  public List<StockItem> findWithExpiredReservations(TenantScope scope, Instant now) {
    return select(scope, "id in (select stock_item_id from inventory.reservation where status = 'RESERVED' and expires_at <= :value)", Timestamp.from(now));
  }

  private List<StockItem> select(TenantScope scope, String condition, Object value) {
    var rows = jdbc.sql("select id, offer_id, seller_id, unique_item, unlimited, on_hand, reserved, revision from inventory.stock_item "
            + "where tenant_id = :tenant and store_id = :store and " + condition + " order by id")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
        .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("offer_id", UUID.class), rs.getObject("seller_id", UUID.class),
            rs.getBoolean("unique_item"), rs.getBoolean("unlimited"), rs.getInt("on_hand"), rs.getInt("reserved"), rs.getLong("revision")})
        .list();
    return rows.stream().map(r -> StockItem.restore((UUID) r[0], scope, (UUID) r[1], (UUID) r[2], (Boolean) r[3], (Boolean) r[4], (Integer) r[5],
        (Integer) r[6], reservations(scope, (UUID) r[0]), (Long) r[7])).toList();
  }

  /** Only open reservations are loaded: closed ones never change again. */
  private List<StockItem.Reservation> reservations(TenantScope scope, UUID itemId) {
    return jdbc.sql("""
        select id, checkout_attempt_id, quantity, expires_at, status from inventory.reservation
        where stock_item_id = :item and tenant_id = :tenant and store_id = :store and status = 'RESERVED' order by created_at""")
        .param("item", itemId).param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new StockItem.Reservation(rs.getObject("id", UUID.class), rs.getObject("checkout_attempt_id", UUID.class), rs.getInt("quantity"),
            rs.getTimestamp("expires_at").toInstant(), StockItem.ReservationStatus.valueOf(rs.getString("status"))))
        .list();
  }
}
