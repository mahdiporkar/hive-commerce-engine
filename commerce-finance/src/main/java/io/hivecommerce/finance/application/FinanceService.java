package io.hivecommerce.finance.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.finance.domain.CommissionCalculator;
import io.hivecommerce.finance.domain.CommissionRule;
import io.hivecommerce.finance.domain.FinanceRepository;
import io.hivecommerce.finance.domain.JournalEntry;
import io.hivecommerce.finance.domain.JournalEntry.Accounts;
import io.hivecommerce.finance.domain.Payout;
import io.hivecommerce.finance.domain.event.FinanceEvent;
import io.hivecommerce.finance.infrastructure.FinanceOutbox;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Marketplace finance (ADR-0011). Ledger flow: payment captured → Dr CASH / Cr CLEARING; order paid → per seller order
 * Dr CLEARING / Cr SELLER_PENDING + COMMISSION_REVENUE + TAX_PAYABLE; settlement close → Dr SELLER_PENDING / Cr
 * SELLER_AVAILABLE; payout approved → Dr SELLER_AVAILABLE / Cr CASH; refund → Dr CLEARING / Cr CASH plus a reversing
 * allocation per refunded line. A seller's available balance may go negative after a refund; it is netted by later
 * settlements and blocks payouts until then.
 */
@Service
public class FinanceService {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final FinanceRepository finance;
  private final FinanceOutbox outbox;
  private final CatalogQueries catalog;
  private final SellerDirectory sellers;
  private final Clock clock;
  private final Duration holdPeriod;
  private final boolean commissionsEnabled;

  public FinanceService(DeploymentScope deployment, TenantTransactions transactions, FinanceRepository finance, FinanceOutbox outbox, CatalogQueries catalog,
      SellerDirectory sellers, Clock clock, @Value("${commerce.finance.settlement-hold:P7D}") Duration holdPeriod,
      @Value("${commerce.capabilities.commissions:false}") boolean commissionsEnabled) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.finance = finance;
    this.outbox = outbox;
    this.catalog = catalog;
    this.sellers = sellers;
    this.clock = clock;
    this.holdPeriod = holdPeriod;
    this.commissionsEnabled = commissionsEnabled;
  }

  // ---------------------------------------------------------------- operator

  public record RuleCommand(String level, UUID targetId, BigDecimal ratePercent, Instant effectiveFrom, Instant effectiveTo) {}

  public record RuleView(UUID ruleId, String level, UUID targetId, BigDecimal ratePercent, Instant effectiveFrom, Instant effectiveTo) {
    static RuleView of(CommissionRule r) { return new RuleView(r.id(), r.level().name(), r.targetId(), r.ratePercent(), r.effectiveFrom(), r.effectiveTo()); }
  }

  public RuleView createRule(UserContext user, RuleCommand c) {
    TenantScope scope = operator(user);
    CommissionRule.Level level;
    try {
      level = CommissionRule.Level.valueOf(String.valueOf(c.level()));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("level", "PRODUCT, SELLER, CATEGORY or STORE");
    }
    var rule = new CommissionRule(Ids.newId(), scope, level, c.targetId(), c.ratePercent(), c.effectiveFrom(), c.effectiveTo());
    return transactions.inScope(scope, () -> {
      finance.insertRule(rule);
      return RuleView.of(rule);
    });
  }

  public List<RuleView> rules(UserContext user) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> finance.rules(scope).stream().map(RuleView::of).toList());
  }

  public record TrialBalance(String currency, Map<String, BigDecimal> accounts, BigDecimal sum) {}

  /** Debits minus credits per account, derived from entries. The sum is always zero in a healthy ledger. */
  public TrialBalance trialBalance(UserContext user, String currency) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> {
      var balances = finance.balances(scope, currency);
      return new TrialBalance(currency, balances, balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    });
  }

  public record SettlementView(UUID settlementId, UUID sellerId, BigDecimal amount, String currency, Instant closedAt) {
    static SettlementView of(FinanceRepository.Settlement s) { return new SettlementView(s.id(), s.sellerId(), s.amount(), s.currency(), s.closedAt()); }
  }

  /** Releases delivered earnings past their hold period to the seller's available balance. */
  public SettlementView closeSettlement(UserContext user, UUID sellerId) {
    TenantScope scope = operator(user);
    if (sellerId == null) throw DomainValidationException.of("sellerId", "is required");
    return transactions.inScope(scope, () -> {
      finance.lockSeller(sellerId);
      var due = finance.releasable(scope, sellerId, clock.instant().minus(holdPeriod));
      var payable = due.stream().filter(e -> e.payable().signum() > 0).toList();
      if (due.isEmpty()) throw new DomainRuleException("NOTHING_TO_SETTLE", "No delivered earnings are past their hold period");
      UUID id = Ids.newId();
      String currency = due.getFirst().currency();
      BigDecimal total = payable.stream().map(FinanceRepository.Earning::payable).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (total.signum() > 0) {
        var amount = FinanceRepository.money(total, currency);
        finance.post(JournalEntry.in(amount.currency()).debit(Accounts.sellerPending(sellerId), amount).credit(Accounts.sellerAvailable(sellerId), amount)
            .build(Ids.newId(), scope, JournalEntry.Type.SETTLEMENT_RELEASE, id.toString()));
      }
      var settlement = new FinanceRepository.Settlement(id, sellerId, total, currency, clock.instant());
      finance.insertSettlement(scope, settlement);
      due.forEach(e -> finance.markReleased(scope, e.sellerOrderId(), id));
      outbox.append(scope, List.of(new FinanceEvent("SettlementClosed", id.toString(), Map.of("settlementId", id.toString(), "sellerId", sellerId.toString(),
          "amount", total.toPlainString(), "currency", currency), clock.instant())));
      return SettlementView.of(settlement);
    });
  }

  public List<SettlementView> settlements(UserContext user, UUID sellerId) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> finance.settlements(scope, sellerId).stream().map(SettlementView::of).toList());
  }

  public record PayoutView(UUID payoutId, UUID sellerId, UUID settlementId, BigDecimal amount, String currency, String status, String reference) {
    static PayoutView of(Payout p) {
      return new PayoutView(p.id(), p.sellerId(), p.settlementId(), p.amount().amount(), p.amount().currency().getCurrencyCode(), p.status().name(), p.reference());
    }
  }

  public List<PayoutView> payouts(UserContext user, UUID sellerId) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> finance.payouts(scope, sellerId).stream().map(PayoutView::of).toList());
  }

  public PayoutView approvePayout(UserContext user, UUID payoutId) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> {
      var payout = finance.payout(scope, payoutId).orElseThrow(() -> HiveException.notFound("Payout not found"));
      finance.lockSeller(payout.sellerId());
      var seller = sellers.seller(scope, payout.sellerId()).orElseThrow();
      if (!"ACTIVE".equals(seller.status())) throw new DomainRuleException("SELLER_NOT_ACTIVE", "Payouts to this seller are on hold");
      String currency = payout.amount().currency().getCurrencyCode();
      var available = finance.balance(scope, Accounts.sellerAvailable(payout.sellerId()), currency).negate();
      payout.approve(FinanceRepository.money(available.max(BigDecimal.ZERO), currency));
      finance.post(JournalEntry.in(payout.amount().currency()).debit(Accounts.sellerAvailable(payout.sellerId()), payout.amount()).credit(Accounts.CASH, payout.amount())
          .build(Ids.newId(), scope, JournalEntry.Type.PAYOUT, payout.id().toString()));
      finance.updatePayout(payout);
      outbox.append(scope, List.of(new FinanceEvent("PayoutApproved", payout.id().toString(), Map.of("payoutId", payout.id().toString(), "sellerId", payout.sellerId().toString(),
          "amount", payout.amount().amount().toPlainString(), "currency", currency), clock.instant())));
      return PayoutView.of(payout);
    });
  }

  public PayoutView rejectPayout(UserContext user, UUID payoutId, String reason) {
    return changePayout(user, payoutId, p -> p.reject(reason));
  }

  public PayoutView markPayoutPaid(UserContext user, UUID payoutId, String reference) {
    return changePayout(user, payoutId, p -> p.markPaid(reference));
  }

  private PayoutView changePayout(UserContext user, UUID payoutId, java.util.function.Consumer<Payout> change) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> {
      var payout = finance.payout(scope, payoutId).orElseThrow(() -> HiveException.notFound("Payout not found"));
      change.accept(payout);
      finance.updatePayout(payout);
      return PayoutView.of(payout);
    });
  }

  // ---------------------------------------------------------------- seller workspace

  public record EarningView(UUID sellerOrderId, UUID orderId, BigDecimal net, BigDecimal refunded, String currency, String status) {}

  public record SellerFinance(UUID sellerId, BigDecimal pending, BigDecimal available, String currency, List<EarningView> earnings, List<SettlementView> settlements,
      List<PayoutView> payouts) {}

  /** A seller's own figures only (Layer 2: finance.view on that seller; 404 otherwise). */
  public SellerFinance sellerFinance(UserContext user, UUID sellerId, String currency) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "finance.view")) throw HiveException.notFound("Seller not found");
      return new SellerFinance(sellerId, finance.balance(scope, Accounts.sellerPending(sellerId), currency).negate(),
          finance.balance(scope, Accounts.sellerAvailable(sellerId), currency).negate(), currency,
          finance.earnings(scope, sellerId).stream().map(e -> new EarningView(e.sellerOrderId(), e.orderId(), e.net(), e.refunded(), e.currency(), e.status())).toList(),
          finance.settlements(scope, sellerId).stream().map(SettlementView::of).toList(), finance.payouts(scope, sellerId).stream().map(PayoutView::of).toList());
    });
  }

  /** One payout per settlement; the amount is capped by what is actually available after refunds. */
  public PayoutView requestPayout(UserContext user, UUID sellerId, UUID settlementId) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "finance.view")) throw HiveException.notFound("Seller not found");
      finance.lockSeller(sellerId);
      var settlement = finance.settlement(scope, settlementId).filter(s -> s.sellerId().equals(sellerId)).orElseThrow(() -> HiveException.notFound("Settlement not found"));
      if (finance.payouts(scope, sellerId).stream().anyMatch(p -> p.settlementId().equals(settlementId))) {
        throw new DomainRuleException("PAYOUT_EXISTS", "This settlement already has a payout");
      }
      var available = finance.balance(scope, Accounts.sellerAvailable(sellerId), settlement.currency()).negate();
      var amount = settlement.amount().min(available).max(BigDecimal.ZERO);
      var payout = Payout.request(Ids.newId(), scope, sellerId, settlementId, FinanceRepository.money(amount, settlement.currency()));
      finance.insertPayout(payout);
      return PayoutView.of(payout);
    });
  }

  private TenantScope operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    return deployment.scope();
  }

  // ---------------------------------------------------------------- event consumers

  /** Payment, Ordering, Fulfillment and Digital Delivery events drive the ledger (context map). */
  @Component
  static class LedgerHandler implements EventHandler {
    private final FinanceService service;

    LedgerHandler(FinanceService service) {
      this.service = service;
    }

    public String consumer() { return "finance.ledger"; }

    public Set<String> eventTypes() {
      return Set.of("commerce.payment.PaymentCaptured", "commerce.payment.RefundCompleted", "commerce.ordering.OrderPaid", "commerce.ordering.OrderLineRefunded",
          "commerce.fulfillment.SellerOrderDelivered", "commerce.digital.DigitalDelivered");
    }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      switch (e.type()) {
        case "commerce.payment.PaymentCaptured" -> service.onCaptured(scope, e.payload());
        case "commerce.payment.RefundCompleted" -> service.onRefund(scope, e.payload());
        case "commerce.ordering.OrderPaid" -> service.onOrderPaid(scope, e.payload());
        case "commerce.ordering.OrderLineRefunded" -> service.onLineRefunded(scope, e.payload());
        default -> service.finance.markDelivered(scope, UUID.fromString((String) e.payload().get("sellerOrderId")), e.occurredAt());
      }
    }
  }

  void onCaptured(TenantScope scope, Map<String, Object> p) {
    Money amount = money((String) p.get("amount"), (String) p.get("currency"));
    finance.post(JournalEntry.in(amount.currency()).debit(Accounts.CASH, amount).credit(Accounts.CLEARING, amount)
        .build(Ids.newId(), scope, JournalEntry.Type.CASH_IN, (String) p.get("paymentId")));
  }

  void onRefund(TenantScope scope, Map<String, Object> p) {
    Money amount = money((String) p.get("refundAmount"), (String) p.get("currency"));
    finance.post(JournalEntry.in(amount.currency()).debit(Accounts.CLEARING, amount).credit(Accounts.CASH, amount)
        .build(Ids.newId(), scope, JournalEntry.Type.CASH_OUT, (String) p.get("refundId")));
  }

  @SuppressWarnings("unchecked")
  void onOrderPaid(TenantScope scope, Map<String, Object> p) {
    String currency = (String) p.get("currency");
    Currency c = Currency.getInstance(currency);
    UUID orderId = UUID.fromString((String) p.get("orderId"));
    Instant now = clock.instant();
    var rules = finance.rules(scope);
    UUID internal = sellers.internalSellerId(scope);
    var lines = (List<Map<String, Object>>) p.get("lines");
    var parents = new HashMap<UUID, UUID>();
    catalog.activeCategories(scope).forEach(n -> parents.put(n.id(), n.parentId()));
    for (var so : (List<Map<String, Object>>) p.get("sellerOrders")) {
      UUID sellerOrderId = UUID.fromString((String) so.get("sellerOrderId"));
      UUID sellerId = UUID.fromString((String) so.get("sellerId"));
      Money shipping = money((String) so.get("shipping"), currency);
      Money zero = new Money(BigDecimal.ZERO, c), subtotal = zero, tax = zero, commission = zero;
      for (var line : lines) {
        if (!sellerOrderId.toString().equals(line.get("sellerOrderId"))) continue;
        Money lineTotal = money((String) line.get("lineTotal"), currency), lineTax = money((String) line.get("tax"), currency);
        UUID productId = UUID.fromString((String) line.get("productId"));
        var chain = categoryChain(scope, productId, parents);
        var rule = commissionsEnabled && !internal.equals(sellerId)
            ? CommissionCalculator.select(rules, new CommissionCalculator.Context(productId, sellerId, chain), now) : java.util.Optional.<CommissionRule>empty();
        BigDecimal rate = rule.map(CommissionRule::ratePercent).orElse(BigDecimal.ZERO);
        Money lineCommission = CommissionCalculator.commission(lineTotal, rate);
        finance.insertCommissionLine(scope, new FinanceRepository.CommissionLine(UUID.fromString((String) line.get("lineId")), sellerOrderId, sellerId, lineTotal.amount(),
            lineTax.amount(), lineCommission.amount(), rate, rule.map(CommissionRule::id).orElse(null), currency));
        subtotal = subtotal.plus(lineTotal);
        tax = tax.plus(lineTax);
        commission = commission.plus(lineCommission);
      }
      Money net = subtotal.minus(commission).plus(shipping);
      finance.post(JournalEntry.in(c).debit(Accounts.CLEARING, subtotal.plus(tax).plus(shipping)).credit(Accounts.sellerPending(sellerId), net)
          .credit(Accounts.COMMISSION_REVENUE, commission).credit(Accounts.TAX_PAYABLE, tax)
          .build(Ids.newId(), scope, JournalEntry.Type.ORDER_ALLOCATION, sellerOrderId.toString()));
      finance.insertEarning(scope, new FinanceRepository.Earning(sellerOrderId, orderId, sellerId, net.amount(), BigDecimal.ZERO, currency, "PENDING", null, null));
      outbox.append(scope, List.of(new FinanceEvent("CommissionCalculated", sellerOrderId.toString(), Map.of("sellerOrderId", sellerOrderId.toString(), "sellerId", sellerId.toString(),
          "amount", commission.amount().toPlainString(), "currency", currency), now)));
    }
  }

  /** Reverses the allocation of one refunded line. Money already released comes out of the available balance. */
  void onLineRefunded(TenantScope scope, Map<String, Object> p) {
    UUID lineId = UUID.fromString((String) p.get("lineId"));
    var line = finance.commissionLine(scope, lineId).orElse(null);
    if (line == null) return; // the order was refunded before it was allocated
    Currency c = Currency.getInstance(line.currency());
    Money sellerPart = new Money(line.lineTotal().subtract(line.commission()), c), commission = new Money(line.commission(), c), tax = new Money(line.tax(), c);
    var earning = finance.earning(scope, line.sellerOrderId()).orElseThrow();
    String account = "RELEASED".equals(earning.status()) ? Accounts.sellerAvailable(line.sellerId()) : Accounts.sellerPending(line.sellerId());
    finance.post(JournalEntry.in(c).debit(account, sellerPart).debit(Accounts.COMMISSION_REVENUE, commission).debit(Accounts.TAX_PAYABLE, tax)
        .credit(Accounts.CLEARING, sellerPart.plus(commission).plus(tax)).build(Ids.newId(), scope, JournalEntry.Type.REFUND_ALLOCATION, lineId.toString()));
    if (!"RELEASED".equals(earning.status())) finance.addRefunded(scope, line.sellerOrderId(), sellerPart.amount());
  }

  private List<UUID> categoryChain(TenantScope scope, UUID productId, Map<UUID, UUID> parents) {
    var chain = new ArrayList<UUID>();
    UUID current = catalog.product(scope, productId).map(CatalogQueries.ProductSnapshot::categoryId).orElse(null);
    while (current != null && !chain.contains(current)) {
      chain.add(current);
      current = parents.get(current);
    }
    return chain;
  }

  private static Money money(String amount, String currency) {
    return Money.rounded(new BigDecimal(amount), Currency.getInstance(currency));
  }
}
