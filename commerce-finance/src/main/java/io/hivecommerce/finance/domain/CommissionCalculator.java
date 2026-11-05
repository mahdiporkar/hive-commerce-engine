package io.hivecommerce.finance.domain;

import io.hivecommerce.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain service: picks the rule that applies to a line and computes its commission, rounded per currency. */
public final class CommissionCalculator {
  public record Context(UUID productId, UUID sellerId, List<UUID> categoryChain) {}

  private CommissionCalculator() {}

  /** Most specific active rule; among categories the one nearest the product wins. Empty when no rule applies. */
  public static Optional<CommissionRule> select(List<CommissionRule> rules, Context line, Instant at) {
    return rules.stream().filter(r -> r.activeAt(at)).filter(r -> switch (r.level()) {
      case PRODUCT -> r.targetId().equals(line.productId());
      case SELLER -> r.targetId().equals(line.sellerId());
      case CATEGORY -> line.categoryChain().contains(r.targetId());
      case STORE -> true;
    }).min(Comparator.<CommissionRule>comparingInt(r -> r.level().ordinal()).thenComparingInt(r ->
        r.level() == CommissionRule.Level.CATEGORY ? line.categoryChain().indexOf(r.targetId()) : 0));
  }

  public static Money commission(Money lineTotal, BigDecimal ratePercent) {
    return Money.rounded(lineTotal.amount().multiply(ratePercent).movePointLeft(2), lineTotal.currency());
  }
}
