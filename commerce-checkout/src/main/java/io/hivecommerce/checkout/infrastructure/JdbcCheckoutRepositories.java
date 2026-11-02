package io.hivecommerce.checkout.infrastructure;

import io.hivecommerce.checkout.domain.Cart;
import io.hivecommerce.checkout.domain.CheckoutAttempt;
import io.hivecommerce.checkout.domain.CheckoutRepositories;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

final class JdbcCheckoutRepositories {
  private JdbcCheckoutRepositories() {}

  @Repository
  static class Carts implements CheckoutRepositories.Carts {
    private final JdbcClient jdbc;

    Carts(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public void insert(Cart c) {
      jdbc.sql("""
          insert into checkout.cart(id, tenant_id, store_id, customer_id, guest_token_hash, status, expires_at)
          values (:id, :tenant, :store, :customer, :hash, :status, :expires)""")
          .param("id", c.id()).param("tenant", c.scope().tenantId()).param("store", c.scope().storeId()).param("customer", c.customerId())
          .param("hash", c.guestTokenHash()).param("status", c.status().name()).param("expires", c.expiresAt() == null ? null : Timestamp.from(c.expiresAt())).update();
      writeItems(c);
    }

    @Override
    public void update(Cart c) {
      int updated = jdbc.sql("""
          update checkout.cart set status = :status, revision = revision + 1, updated_at = now()
          where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
          .param("status", c.status().name()).param("id", c.id()).param("tenant", c.scope().tenantId()).param("store", c.scope().storeId())
          .param("revision", c.revision()).update();
      if (updated != 1) throw new OptimisticLockingFailureException("cart " + c.id() + " changed concurrently");
      jdbc.sql("delete from checkout.cart_item where cart_id = :id and tenant_id = :tenant and store_id = :store")
          .param("id", c.id()).param("tenant", c.scope().tenantId()).param("store", c.scope().storeId()).update();
      writeItems(c);
    }

    private void writeItems(Cart c) {
      int position = 0;
      for (var i : c.items()) {
        jdbc.sql("""
            insert into checkout.cart_item(id, tenant_id, store_id, cart_id, offer_id, quantity, unique_item, position)
            values (:id, :tenant, :store, :cart, :offer, :quantity, :unique, :position)""")
            .param("id", i.id()).param("tenant", c.scope().tenantId()).param("store", c.scope().storeId()).param("cart", c.id()).param("offer", i.offerId())
            .param("quantity", i.quantity()).param("unique", i.uniqueItem()).param("position", position++).update();
      }
    }

    @Override
    public Optional<Cart> findById(TenantScope scope, UUID id) { return select(scope, "id = :value", id); }

    @Override
    public Optional<Cart> findActiveByCustomer(TenantScope scope, UUID customerId) { return select(scope, "customer_id = :value and status = 'ACTIVE'", customerId); }

    @Override
    public Optional<Cart> findActiveByTokenHash(TenantScope scope, String hash) { return select(scope, "guest_token_hash = :value and status = 'ACTIVE'", hash); }

    private Optional<Cart> select(TenantScope scope, String condition, Object value) {
      return jdbc.sql("select id, customer_id, guest_token_hash, status, expires_at, revision from checkout.cart where tenant_id = :tenant and store_id = :store and "
              + condition + " order by created_at desc limit 1")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
          .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("guest_token_hash"),
              rs.getString("status"), rs.getTimestamp("expires_at"), rs.getLong("revision")})
          .optional()
          .map(r -> Cart.restore((UUID) r[0], scope, (UUID) r[1], (String) r[2], Cart.Status.valueOf((String) r[3]),
              jdbc.sql("select id, offer_id, quantity, unique_item from checkout.cart_item where cart_id = :cart and tenant_id = :tenant and store_id = :store order by position")
                  .param("cart", r[0]).param("tenant", scope.tenantId()).param("store", scope.storeId())
                  .query((rs, n) -> new Cart.Item(rs.getObject("id", UUID.class), rs.getObject("offer_id", UUID.class), rs.getInt("quantity"), rs.getBoolean("unique_item")))
                  .list(),
              r[4] == null ? null : ((Timestamp) r[4]).toInstant(), (Long) r[5]));
    }
  }

  @Repository
  static class Attempts implements CheckoutRepositories.Attempts {
    private final JdbcClient jdbc;

    Attempts(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public void insert(CheckoutAttempt a) {
      jdbc.sql("""
          insert into checkout.checkout_attempt(id, tenant_id, store_id, customer_id, idempotency_key, cart_id, state)
          values (:id, :tenant, :store, :customer, :key, :cart, :state)""")
          .param("id", a.id()).param("tenant", a.scope().tenantId()).param("store", a.scope().storeId()).param("customer", a.customerId())
          .param("key", a.idempotencyKey()).param("cart", a.cartId()).param("state", a.state().name()).update();
    }

    @Override
    public void update(CheckoutAttempt a) {
      int updated = jdbc.sql("""
          update checkout.checkout_attempt set state = :state, order_id = :order, order_number = :number, total = :total, currency = :currency,
            payment_id = :payment, redirect_url = :redirect, failure_code = :failure, revision = revision + 1, updated_at = now()
          where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
          .param("state", a.state().name()).param("order", a.orderId()).param("number", a.orderNumber())
          .param("total", a.total() == null ? null : a.total().amount()).param("currency", a.total() == null ? null : a.total().currency().getCurrencyCode())
          .param("payment", a.paymentId()).param("redirect", a.redirectUrl()).param("failure", a.failureCode()).param("id", a.id())
          .param("tenant", a.scope().tenantId()).param("store", a.scope().storeId()).param("revision", a.revision()).update();
      if (updated != 1) throw new OptimisticLockingFailureException("checkout " + a.id() + " changed concurrently");
    }

    @Override
    public Optional<CheckoutAttempt> findById(TenantScope scope, UUID id) { return select(scope, "id = :a", id, null); }

    @Override
    public Optional<CheckoutAttempt> findByKey(TenantScope scope, UUID customerId, String key) { return select(scope, "customer_id = :a and idempotency_key = :b", customerId, key); }

    private Optional<CheckoutAttempt> select(TenantScope scope, String condition, Object a, Object b) {
      var statement = jdbc.sql("select id, customer_id, idempotency_key, cart_id, state, order_id, order_number, total, currency, payment_id, redirect_url, failure_code, revision "
              + "from checkout.checkout_attempt where tenant_id = :tenant and store_id = :store and " + condition)
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("a", a);
      if (b != null) statement = statement.param("b", b);
      return statement.query((rs, n) -> CheckoutAttempt.restore(rs.getObject("id", UUID.class), scope, rs.getObject("customer_id", UUID.class),
              rs.getString("idempotency_key"), rs.getObject("cart_id", UUID.class), CheckoutAttempt.State.valueOf(rs.getString("state")),
              rs.getObject("order_id", UUID.class), rs.getString("order_number"),
              rs.getBigDecimal("total") == null ? null : new Money(rs.getBigDecimal("total"), Currency.getInstance(rs.getString("currency"))),
              rs.getObject("payment_id", UUID.class), rs.getString("redirect_url"), rs.getString("failure_code"), rs.getLong("revision")))
          .optional();
    }
  }
}
