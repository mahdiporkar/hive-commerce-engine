package io.hivecommerce.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinanceDomainTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  private static final Currency IRR = Currency.getInstance("IRR");
  private final UUID product = UUID.randomUUID(), seller = UUID.randomUUID(), category = UUID.randomUUID(), parent = UUID.randomUUID();

  private CommissionRule rule(CommissionRule.Level level, UUID target, String rate) {
    return new CommissionRule(UUID.randomUUID(), SCOPE, level, target, new BigDecimal(rate), null, null);
  }

  @Test
  void journalsMustBalanceAndDropZeroLines() {
    var entry = JournalEntry.in(IRR).debit("A", Money.of("100", "IRR")).credit("B", Money.of("60", "IRR")).credit("C", Money.of("40", "IRR")).credit("D", Money.of("0", "IRR"))
        .build(UUID.randomUUID(), SCOPE, JournalEntry.Type.CASH_IN, "s1");
    assertThat(entry.lines()).hasSize(3);
    assertThatThrownBy(() -> JournalEntry.in(IRR).debit("A", Money.of("100", "IRR")).credit("B", Money.of("99", "IRR")).build(UUID.randomUUID(), SCOPE, JournalEntry.Type.CASH_IN, "s2"))
        .isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("JOURNAL_UNBALANCED"));
    assertThatThrownBy(() -> JournalEntry.in(IRR).debit("A", Money.of("1.00", "USD"))).isInstanceOf(DomainRuleException.class);
  }

  @Test
  void mostSpecificCommissionRuleWins() {
    var store = rule(CommissionRule.Level.STORE, null, "10");
    var parentRule = rule(CommissionRule.Level.CATEGORY, parent, "12");
    var categoryRule = rule(CommissionRule.Level.CATEGORY, category, "13");
    var sellerRule = rule(CommissionRule.Level.SELLER, seller, "15");
    var productRule = rule(CommissionRule.Level.PRODUCT, product, "20");
    var ctx = new CommissionCalculator.Context(product, seller, List.of(category, parent));
    assertThat(CommissionCalculator.select(List.of(store), ctx, NOW)).contains(store);
    assertThat(CommissionCalculator.select(List.of(store, parentRule), ctx, NOW)).contains(parentRule);
    assertThat(CommissionCalculator.select(List.of(store, parentRule, categoryRule), ctx, NOW)).contains(categoryRule);
    assertThat(CommissionCalculator.select(List.of(store, categoryRule, sellerRule), ctx, NOW)).contains(sellerRule);
    assertThat(CommissionCalculator.select(List.of(store, sellerRule, productRule), ctx, NOW)).contains(productRule);
    var expired = new CommissionRule(UUID.randomUUID(), SCOPE, CommissionRule.Level.PRODUCT, product, BigDecimal.ONE, null, NOW.minusSeconds(1));
    assertThat(CommissionCalculator.select(List.of(store, expired), ctx, NOW)).contains(store);
    assertThat(CommissionCalculator.commission(Money.of("333333", "IRR"), new BigDecimal("10"))).isEqualTo(Money.of("33333", "IRR"));
    assertThatThrownBy(() -> rule(CommissionRule.Level.SELLER, null, "5")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> rule(CommissionRule.Level.STORE, null, "101")).isInstanceOf(DomainValidationException.class);
  }

  @Test
  void payoutIsApprovedOnceAndNeverAboveTheBalance() {
    var payout = Payout.request(UUID.randomUUID(), SCOPE, seller, UUID.randomUUID(), Money.of("100", "IRR"));
    assertThatThrownBy(() -> payout.approve(Money.of("99", "IRR"))).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("PAYOUT_EXCEEDS_BALANCE"));
    payout.approve(Money.of("100", "IRR"));
    assertThatThrownBy(() -> payout.approve(Money.of("100", "IRR"))).isInstanceOf(DomainRuleException.class);
    assertThatThrownBy(() -> payout.reject("late")).isInstanceOf(DomainRuleException.class);
    payout.markPaid("BANK-1");
    assertThat(payout.status()).isEqualTo(Payout.Status.PAID);
    assertThatThrownBy(() -> Payout.request(UUID.randomUUID(), SCOPE, seller, UUID.randomUUID(), Money.of("0", "IRR"))).isInstanceOf(DomainRuleException.class);
  }
}
