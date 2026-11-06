package io.hivecommerce.payment.infrastructure;

import io.hivecommerce.payment.domain.Payment;
import io.hivecommerce.payment.domain.PaymentRepository;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPaymentRepository implements PaymentRepository {
  private final JdbcClient jdbc;

  JdbcPaymentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Payment p) {
    jdbc.sql("""
        insert into payment.payment(id, tenant_id, store_id, order_id, checkout_attempt_id, customer_id, amount, currency, provider, provider_ref, status, provider_tx_id)
        values (:id, :tenant, :store, :order, :attempt, :customer, :amount, :currency, :provider, :ref, :status, :tx)""")
        .param("id", p.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("order", p.orderId())
        .param("attempt", p.checkoutAttemptId()).param("customer", p.customerId()).param("amount", p.amount().amount())
        .param("currency", p.amount().currency().getCurrencyCode()).param("provider", p.provider()).param("ref", p.providerRef())
        .param("status", p.status().name()).param("tx", p.providerTxId()).update();
  }

  @Override
  public void update(Payment p) {
    int updated = jdbc.sql("""
        update payment.payment set provider_ref = :ref, status = :status, provider_tx_id = :tx, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("ref", p.providerRef()).param("status", p.status().name()).param("tx", p.providerTxId()).param("id", p.id())
        .param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("revision", p.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("payment " + p.id() + " changed concurrently");
    for (var r : p.refunds()) {
      jdbc.sql("""
          insert into payment.refund(id, tenant_id, store_id, payment_id, idempotency_key, amount, order_line_ids, status, provider_refund_id)
          values (:id, :tenant, :store, :payment, :key, :amount, :lines, :status, :providerRefund)
          on conflict (id) do update set status = excluded.status, provider_refund_id = excluded.provider_refund_id, updated_at = now()
          where payment.refund.status = 'REQUESTED'""")
          .param("id", r.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("payment", p.id())
          .param("key", r.idempotencyKey()).param("amount", r.amount().amount()).param("lines", r.orderLineIds().toArray(UUID[]::new))
          .param("status", r.status().name()).param("providerRefund", r.providerRefundId()).update();
    }
  }

  @Override
  public Optional<Payment> findById(TenantScope scope, UUID id) { return select(scope, "id = :value", id).stream().findFirst(); }

  @Override
  public Optional<Payment> findByAttempt(TenantScope scope, UUID attemptId) { return select(scope, "checkout_attempt_id = :value", attemptId).stream().findFirst(); }

  @Override
  public Optional<Payment> findByOrder(TenantScope scope, UUID orderId) { return select(scope, "order_id = :value", orderId).stream().findFirst(); }

  @Override
  public Optional<Payment> findByProviderRef(TenantScope scope, String provider, String ref) {
    return select(scope, "provider_ref = :value", ref).stream().filter(p -> p.provider().equals(provider)).findFirst();
  }

  @Override
  public List<Payment> findInitiatedBefore(TenantScope scope, Instant before) {
    return select(scope, "status = 'INITIATED' and created_at < :value", Timestamp.from(before));
  }

  @Override
  public List<Payment> findRecent(TenantScope scope, int limit) {
    return jdbc.sql("select id from payment.payment where tenant_id = :tenant and store_id = :store order by created_at desc limit :limit")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("limit", limit).query(java.util.UUID.class).list().stream()
        .map(id -> findById(scope, id).orElseThrow()).toList();
  }

  @Override
  public boolean recordWebhook(TenantScope scope, String provider, String eventId, String ref, String body) {
    return jdbc.sql("""
        insert into payment.webhook_inbox(id, tenant_id, store_id, provider, provider_event_id, provider_ref, body)
        values (gen_random_uuid(), :tenant, :store, :provider, :event, :ref, :body) on conflict (provider, provider_event_id) do nothing""")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("provider", provider).param("event", eventId).param("ref", ref)
        .param("body", body.length() > 20000 ? body.substring(0, 20000) : body).update() == 1;
  }

  private List<Payment> select(TenantScope scope, String condition, Object value) {
    var rows = jdbc.sql("select id, order_id, checkout_attempt_id, customer_id, amount, currency, provider, provider_ref, status, provider_tx_id, revision "
            + "from payment.payment where tenant_id = :tenant and store_id = :store and " + condition + " order by created_at")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
        .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("checkout_attempt_id", UUID.class),
            rs.getObject("customer_id", UUID.class), new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))), rs.getString("provider"),
            rs.getString("provider_ref"), rs.getString("status"), rs.getString("provider_tx_id"), rs.getLong("revision")})
        .list();
    return rows.stream().map(r -> Payment.restore((UUID) r[0], scope, (UUID) r[1], (UUID) r[2], (UUID) r[3], (Money) r[4], (String) r[5], (String) r[6],
        Payment.Status.valueOf((String) r[7]), (String) r[8], refunds(scope, (UUID) r[0], ((Money) r[4]).currency()), (Long) r[9])).toList();
  }

  private List<Payment.Refund> refunds(TenantScope scope, UUID paymentId, Currency currency) {
    return jdbc.sql("select id, idempotency_key, amount, order_line_ids, status, provider_refund_id from payment.refund where payment_id = :p and tenant_id = :tenant and store_id = :store order by created_at")
        .param("p", paymentId).param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new Payment.Refund(rs.getObject("id", UUID.class), rs.getString("idempotency_key"), new Money(rs.getBigDecimal("amount"), currency),
            Arrays.asList((UUID[]) rs.getArray("order_line_ids").getArray()), Payment.RefundStatus.valueOf(rs.getString("status")), rs.getString("provider_refund_id")))
        .list();
  }
}
