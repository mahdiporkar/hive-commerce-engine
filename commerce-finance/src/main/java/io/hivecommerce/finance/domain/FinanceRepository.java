package io.hivecommerce.finance.domain;

import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the Finance context. Ledger rows are insert-only. */
public interface FinanceRepository {
  /** @return false when an entry with the same type and source already exists (idempotent posting) */
  boolean post(JournalEntry entry);

  Map<String, BigDecimal> balances(TenantScope scope, String currency);

  /** Debits minus credits of one account. */
  BigDecimal balance(TenantScope scope, String account, String currency);

  /** Serializes balance-sensitive work per seller inside the current transaction. */
  void lockSeller(UUID sellerId);

  void insertRule(CommissionRule rule);

  List<CommissionRule> rules(TenantScope scope);

  record CommissionLine(UUID lineId, UUID sellerOrderId, UUID sellerId, BigDecimal lineTotal, BigDecimal tax, BigDecimal commission, BigDecimal ratePercent, UUID ruleId, String currency) {}

  void insertCommissionLine(TenantScope scope, CommissionLine line);

  Optional<CommissionLine> commissionLine(TenantScope scope, UUID lineId);

  record Earning(UUID sellerOrderId, UUID orderId, UUID sellerId, BigDecimal net, BigDecimal refunded, String currency, String status, Instant deliveredAt, UUID settlementId) {
    public BigDecimal payable() { return net.subtract(refunded); }
  }

  void insertEarning(TenantScope scope, Earning earning);

  Optional<Earning> earning(TenantScope scope, UUID sellerOrderId);

  void markDelivered(TenantScope scope, UUID sellerOrderId, Instant at);

  void addRefunded(TenantScope scope, UUID sellerOrderId, BigDecimal amount);

  List<Earning> earnings(TenantScope scope, UUID sellerId);

  List<Earning> releasable(TenantScope scope, UUID sellerId, Instant deliveredBefore);

  void markReleased(TenantScope scope, UUID sellerOrderId, UUID settlementId);

  record Settlement(UUID id, UUID sellerId, BigDecimal amount, String currency, Instant closedAt) {}

  void insertSettlement(TenantScope scope, Settlement settlement);

  Optional<Settlement> settlement(TenantScope scope, UUID id);

  List<Settlement> settlements(TenantScope scope, UUID sellerIdOrNull);

  /** @throws io.hivecommerce.shared.DomainRuleException PAYOUT_EXISTS when the settlement already has a payout */
  void insertPayout(Payout payout);

  void updatePayout(Payout payout);

  Optional<Payout> payout(TenantScope scope, UUID id);

  List<Payout> payouts(TenantScope scope, UUID sellerIdOrNull);

  static Money money(BigDecimal amount, String currency) { return new Money(amount, java.util.Currency.getInstance(currency)); }
}
