package io.hivecommerce.finance.infrastructure;

import io.hivecommerce.finance.domain.CommissionRule;
import io.hivecommerce.finance.domain.FinanceRepository;
import io.hivecommerce.finance.domain.JournalEntry;
import io.hivecommerce.finance.domain.Payout;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFinanceRepository implements FinanceRepository {
  private final JdbcClient jdbc;

  JdbcFinanceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Timestamp ts(Instant at) { return at == null ? null : Timestamp.from(at); }

  private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

  @Override
  public boolean post(JournalEntry e) {
    int inserted = jdbc.sql("""
        insert into finance.journal_entry(id, tenant_id, store_id, type, source_id, currency) values (:id, :tenant, :store, :type, :source, :currency)
        on conflict (tenant_id, store_id, type, source_id) do nothing""")
        .param("id", e.id()).param("tenant", e.scope().tenantId()).param("store", e.scope().storeId()).param("type", e.type().name())
        .param("source", e.sourceId()).param("currency", e.currency()).update();
    if (inserted == 0) return false;
    for (var l : e.lines()) {
      jdbc.sql("""
          insert into finance.journal_line(id, tenant_id, store_id, entry_id, account, debit, credit, currency)
          values (gen_random_uuid(), :tenant, :store, :entry, :account, :debit, :credit, :currency)""")
          .param("tenant", e.scope().tenantId()).param("store", e.scope().storeId()).param("entry", e.id()).param("account", l.account())
          .param("debit", l.debit()).param("credit", l.credit()).param("currency", e.currency()).update();
    }
    return true;
  }

  @Override
  public Map<String, BigDecimal> balances(TenantScope scope, String currency) {
    var result = new TreeMap<String, BigDecimal>();
    jdbc.sql("select account, sum(debit) - sum(credit) as balance from finance.journal_line where tenant_id = :tenant and store_id = :store and currency = :currency group by account")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("currency", currency)
        .query((rs, n) -> result.put(rs.getString("account"), rs.getBigDecimal("balance"))).list();
    return result;
  }

  @Override
  public BigDecimal balance(TenantScope scope, String account, String currency) {
    return jdbc.sql("select coalesce(sum(debit) - sum(credit), 0) from finance.journal_line where tenant_id = :tenant and store_id = :store and account = :account and currency = :currency")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("account", account).param("currency", currency).query(BigDecimal.class).single();
  }

  @Override
  public void lockSeller(UUID sellerId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "finance-seller:" + sellerId).query((rs, n) -> 1).single();
  }

  @Override
  public void insertRule(CommissionRule r) {
    jdbc.sql("""
        insert into finance.commission_rule(id, tenant_id, store_id, level, target_id, rate_percent, effective_from, effective_to)
        values (:id, :tenant, :store, :level, :target, :rate, :from, :to)""")
        .param("id", r.id()).param("tenant", r.scope().tenantId()).param("store", r.scope().storeId()).param("level", r.level().name()).param("target", r.targetId())
        .param("rate", r.ratePercent()).param("from", ts(r.effectiveFrom())).param("to", ts(r.effectiveTo())).update();
  }

  @Override
  public List<CommissionRule> rules(TenantScope scope) {
    return jdbc.sql("select id, level, target_id, rate_percent, effective_from, effective_to from finance.commission_rule where tenant_id = :tenant and store_id = :store order by created_at")
        .param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new CommissionRule(rs.getObject("id", UUID.class), scope, CommissionRule.Level.valueOf(rs.getString("level")), rs.getObject("target_id", UUID.class),
            rs.getBigDecimal("rate_percent"), instant(rs.getTimestamp("effective_from")), instant(rs.getTimestamp("effective_to"))))
        .list();
  }

  @Override
  public void insertCommissionLine(TenantScope scope, CommissionLine l) {
    jdbc.sql("""
        insert into finance.commission_line(line_id, tenant_id, store_id, seller_order_id, seller_id, line_total, tax, commission, rate_percent, rule_id, currency)
        values (:line, :tenant, :store, :so, :seller, :total, :tax, :commission, :rate, :rule, :currency) on conflict (line_id) do nothing""")
        .param("line", l.lineId()).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("so", l.sellerOrderId()).param("seller", l.sellerId())
        .param("total", l.lineTotal()).param("tax", l.tax()).param("commission", l.commission()).param("rate", l.ratePercent()).param("rule", l.ruleId())
        .param("currency", l.currency()).update();
  }

  @Override
  public Optional<CommissionLine> commissionLine(TenantScope scope, UUID lineId) {
    return jdbc.sql("select seller_order_id, seller_id, line_total, tax, commission, rate_percent, rule_id, currency from finance.commission_line where line_id = :line and tenant_id = :tenant and store_id = :store")
        .param("line", lineId).param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> new CommissionLine(lineId, rs.getObject("seller_order_id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getBigDecimal("line_total"),
            rs.getBigDecimal("tax"), rs.getBigDecimal("commission"), rs.getBigDecimal("rate_percent"), rs.getObject("rule_id", UUID.class), rs.getString("currency")))
        .optional();
  }

  @Override
  public void insertEarning(TenantScope scope, Earning e) {
    jdbc.sql("""
        insert into finance.seller_earning(seller_order_id, tenant_id, store_id, order_id, seller_id, net, refunded, currency, status)
        values (:so, :tenant, :store, :order, :seller, :net, 0, :currency, 'PENDING') on conflict (seller_order_id) do nothing""")
        .param("so", e.sellerOrderId()).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("order", e.orderId()).param("seller", e.sellerId())
        .param("net", e.net()).param("currency", e.currency()).update();
  }

  private static final String EARNING = "select seller_order_id, order_id, seller_id, net, refunded, currency, status, delivered_at, settlement_id from finance.seller_earning where tenant_id = :tenant and store_id = :store and ";

  private List<Earning> earnings(TenantScope scope, String condition, java.util.function.UnaryOperator<JdbcClient.StatementSpec> bind) {
    return bind.apply(jdbc.sql(EARNING + condition + " order by created_at").param("tenant", scope.tenantId()).param("store", scope.storeId()))
        .query((rs, n) -> new Earning(rs.getObject("seller_order_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getBigDecimal("net"),
            rs.getBigDecimal("refunded"), rs.getString("currency"), rs.getString("status"), instant(rs.getTimestamp("delivered_at")), rs.getObject("settlement_id", UUID.class)))
        .list();
  }

  @Override
  public Optional<Earning> earning(TenantScope scope, UUID sellerOrderId) {
    return earnings(scope, "seller_order_id = :so", s -> s.param("so", sellerOrderId)).stream().findFirst();
  }

  @Override
  public List<Earning> earnings(TenantScope scope, UUID sellerId) {
    return earnings(scope, "seller_id = :seller", s -> s.param("seller", sellerId));
  }

  @Override
  public List<Earning> releasable(TenantScope scope, UUID sellerId, Instant deliveredBefore) {
    return earnings(scope, "seller_id = :seller and status = 'PENDING' and delivered_at is not null and delivered_at <= :before",
        s -> s.param("seller", sellerId).param("before", ts(deliveredBefore)));
  }

  @Override
  public void markDelivered(TenantScope scope, UUID sellerOrderId, Instant at) {
    jdbc.sql("update finance.seller_earning set delivered_at = coalesce(delivered_at, :at) where seller_order_id = :so and tenant_id = :tenant and store_id = :store")
        .param("at", ts(at)).param("so", sellerOrderId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
  }

  @Override
  public void addRefunded(TenantScope scope, UUID sellerOrderId, BigDecimal amount) {
    jdbc.sql("update finance.seller_earning set refunded = refunded + :amount where seller_order_id = :so and tenant_id = :tenant and store_id = :store")
        .param("amount", amount).param("so", sellerOrderId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
  }

  @Override
  public void markReleased(TenantScope scope, UUID sellerOrderId, UUID settlementId) {
    jdbc.sql("update finance.seller_earning set status = 'RELEASED', settlement_id = :settlement where seller_order_id = :so and tenant_id = :tenant and store_id = :store")
        .param("settlement", settlementId).param("so", sellerOrderId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
  }

  @Override
  public void insertSettlement(TenantScope scope, Settlement s) {
    jdbc.sql("insert into finance.settlement(id, tenant_id, store_id, seller_id, amount, currency, closed_at) values (:id, :tenant, :store, :seller, :amount, :currency, :closed)")
        .param("id", s.id()).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("seller", s.sellerId()).param("amount", s.amount())
        .param("currency", s.currency()).param("closed", ts(s.closedAt())).update();
  }

  private List<Settlement> settlements(TenantScope scope, String condition, Object value) {
    var statement = jdbc.sql("select id, seller_id, amount, currency, closed_at from finance.settlement where tenant_id = :tenant and store_id = :store" + condition + " order by closed_at")
        .param("tenant", scope.tenantId()).param("store", scope.storeId());
    if (value != null) statement = statement.param("value", value);
    return statement.query((rs, n) -> new Settlement(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"),
        instant(rs.getTimestamp("closed_at")))).list();
  }

  @Override
  public Optional<Settlement> settlement(TenantScope scope, UUID id) {
    return settlements(scope, " and id = :value", id).stream().findFirst();
  }

  @Override
  public List<Settlement> settlements(TenantScope scope, UUID sellerId) {
    return sellerId == null ? settlements(scope, "", null) : settlements(scope, " and seller_id = :value", sellerId);
  }

  @Override
  public void insertPayout(Payout p) {
    try {
      jdbc.sql("""
          insert into finance.payout(id, tenant_id, store_id, seller_id, settlement_id, amount, currency, status)
          values (:id, :tenant, :store, :seller, :settlement, :amount, :currency, :status)""")
          .param("id", p.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("seller", p.sellerId()).param("settlement", p.settlementId())
          .param("amount", p.amount().amount()).param("currency", p.amount().currency().getCurrencyCode()).param("status", p.status().name()).update();
    } catch (DuplicateKeyException e) {
      throw new DomainRuleException("PAYOUT_EXISTS", "This settlement already has a payout");
    }
  }

  @Override
  public void updatePayout(Payout p) {
    int updated = jdbc.sql("""
        update finance.payout set status = :status, reference = :reference, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("status", p.status().name()).param("reference", p.reference()).param("id", p.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId())
        .param("revision", p.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("payout " + p.id() + " changed concurrently");
  }

  private List<Payout> payouts(TenantScope scope, String condition, Object value) {
    var statement = jdbc.sql("select id, seller_id, settlement_id, amount, currency, status, reference, revision from finance.payout where tenant_id = :tenant and store_id = :store" + condition + " order by created_at")
        .param("tenant", scope.tenantId()).param("store", scope.storeId());
    if (value != null) statement = statement.param("value", value);
    return statement.query((rs, n) -> new Payout(rs.getObject("id", UUID.class), scope, rs.getObject("seller_id", UUID.class), rs.getObject("settlement_id", UUID.class),
        FinanceRepository.money(rs.getBigDecimal("amount"), rs.getString("currency")), Payout.Status.valueOf(rs.getString("status")), rs.getString("reference"), rs.getLong("revision"))).list();
  }

  @Override
  public Optional<Payout> payout(TenantScope scope, UUID id) {
    return payouts(scope, " and id = :value", id).stream().findFirst();
  }

  @Override
  public List<Payout> payouts(TenantScope scope, UUID sellerId) {
    return sellerId == null ? payouts(scope, "", null) : payouts(scope, " and seller_id = :value", sellerId);
  }
}
