package io.hivecommerce.ordering.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.ordering.domain.Order;
import io.hivecommerce.ordering.domain.OrderRepository;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderRepository implements OrderRepository {
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcOrderRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  @Override
  public void insert(Order o) {
    var s = o.scope();
    jdbc.sql("""
        insert into ordering.customer_order(id, tenant_id, store_id, number, customer_id, checkout_attempt_id, status, total, currency, payment_id)
        values (:id, :tenant, :store, :number, :customer, :attempt, :status, :total, :currency, :payment)""")
        .param("id", o.id()).param("tenant", s.tenantId()).param("store", s.storeId()).param("number", o.number()).param("customer", o.customerId())
        .param("attempt", o.checkoutAttemptId()).param("status", o.status().name()).param("total", o.total().amount())
        .param("currency", o.total().currency().getCurrencyCode()).param("payment", o.paymentId()).update();
    for (var so : o.sellerOrders()) {
      jdbc.sql("""
          insert into ordering.seller_order(id, tenant_id, store_id, order_id, seller_id, status, shipping)
          values (:id, :tenant, :store, :order, :seller, :status, :shipping)""")
          .param("id", so.id()).param("tenant", s.tenantId()).param("store", s.storeId()).param("order", o.id()).param("seller", so.sellerId())
          .param("status", so.status().name()).param("shipping", so.shipping().amount()).update();
      for (var l : so.lines()) {
        jdbc.sql("""
            insert into ordering.order_line(id, tenant_id, store_id, seller_order_id, offer_id, product_id, variant_id, kind, sku, title, variant_attributes,
              unit_price, quantity, line_total, tax)
            values (:id, :tenant, :store, :so, :offer, :product, :variant, :kind, :sku, cast(:title as jsonb), cast(:attrs as jsonb), :unit, :qty, :total, :tax)""")
            .param("id", l.id()).param("tenant", s.tenantId()).param("store", s.storeId()).param("so", so.id()).param("offer", l.offerId())
            .param("product", l.productId()).param("variant", l.variantId()).param("kind", l.kind()).param("sku", l.sku()).param("title", write(l.title()))
            .param("attrs", write(l.variantAttributes())).param("unit", l.unitPrice().amount()).param("qty", l.quantity()).param("total", l.lineTotal().amount())
            .param("tax", l.tax().amount()).update();
      }
    }
  }

  @Override
  public void update(Order o) {
    var s = o.scope();
    int updated = jdbc.sql("""
        update ordering.customer_order set status = :status, payment_id = :payment, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("status", o.status().name()).param("payment", o.paymentId()).param("id", o.id()).param("tenant", s.tenantId()).param("store", s.storeId())
        .param("revision", o.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("order " + o.id() + " changed concurrently");
    for (var so : o.sellerOrders()) {
      jdbc.sql("update ordering.seller_order set status = :status, updated_at = now() where id = :id and tenant_id = :tenant and store_id = :store")
          .param("status", so.status().name()).param("id", so.id()).param("tenant", s.tenantId()).param("store", s.storeId()).update();
    }
  }

  @Override
  public Optional<Order> findById(TenantScope scope, UUID id) { return select(scope, "id = :value", id, 1).stream().findFirst(); }

  @Override
  public Optional<Order> findByAttempt(TenantScope scope, UUID attemptId) { return select(scope, "checkout_attempt_id = :value", attemptId, 1).stream().findFirst(); }

  @Override
  public List<Order> findByCustomer(TenantScope scope, UUID customerId, int limit) { return select(scope, "customer_id = :value", customerId, limit); }

  @Override
  public List<Order> findRecent(TenantScope scope, int limit) { return select(scope, ":value::text is null", null, limit); }

  @Override
  public Optional<Order> findBySellerOrder(TenantScope scope, UUID sellerOrderId) {
    return select(scope, "id = (select order_id from ordering.seller_order where id = :value)", sellerOrderId, 1).stream().findFirst();
  }

  private List<Order> select(TenantScope scope, String condition, Object value, int limit) {
    var rows = jdbc.sql("select id, number, customer_id, checkout_attempt_id, status, total, currency, payment_id, revision from ordering.customer_order "
            + "where tenant_id = :tenant and store_id = :store and " + condition + " order by created_at desc limit :limit")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value).param("limit", limit)
        .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("customer_id", UUID.class),
            rs.getObject("checkout_attempt_id", UUID.class), rs.getString("status"), new Money(rs.getBigDecimal("total"), Currency.getInstance(rs.getString("currency"))),
            rs.getObject("payment_id", UUID.class), rs.getLong("revision")})
        .list();
    return rows.stream().map(r -> Order.restore((UUID) r[0], scope, (String) r[1], (UUID) r[2], (UUID) r[3], Order.Status.valueOf((String) r[4]),
        sellerOrders(scope, (UUID) r[0], ((Money) r[5]).currency()), (Money) r[5], (UUID) r[6], (Long) r[7])).toList();
  }

  private List<Order.SellerOrder> sellerOrders(TenantScope scope, UUID orderId, Currency currency) {
    var rows = jdbc.sql("select id, seller_id, status, shipping from ordering.seller_order where order_id = :order and tenant_id = :tenant and store_id = :store order by id")
        .param("order", orderId).param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getString("status"), rs.getBigDecimal("shipping")})
        .list();
    return rows.stream().map(r -> new Order.SellerOrder((UUID) r[0], (UUID) r[1], Order.SellerOrderStatus.valueOf((String) r[2]),
        lines(scope, (UUID) r[0], currency), new Money((java.math.BigDecimal) r[3], currency))).toList();
  }

  private List<Order.Line> lines(TenantScope scope, UUID sellerOrderId, Currency currency) {
    return jdbc.sql("""
        select id, offer_id, product_id, variant_id, kind, sku, title, variant_attributes, unit_price, quantity, line_total, tax from ordering.order_line
        where seller_order_id = :so and tenant_id = :tenant and store_id = :store order by id""")
        .param("so", sellerOrderId).param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new Order.Line(rs.getObject("id", UUID.class), rs.getObject("offer_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getObject("variant_id", UUID.class), rs.getString("kind"), rs.getString("sku"), read(rs.getString("title"), new TypeReference<Map<String, String>>() {}),
            read(rs.getString("variant_attributes"), new TypeReference<Map<String, Object>>() {}), new Money(rs.getBigDecimal("unit_price"), currency),
            rs.getInt("quantity"), new Money(rs.getBigDecimal("line_total"), currency), new Money(rs.getBigDecimal("tax"), currency)))
        .list();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
