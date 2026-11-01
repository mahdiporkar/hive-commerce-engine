package io.hivecommerce.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;

/**
 * Exact amount in one ISO-4217 currency (ADR-0011). The scale is the currency's minor units (IRR has none) and
 * rounding is HALF_EVEN. Amounts in different currencies are never combined. Toman is a display concern only.
 */
public record Money(BigDecimal amount, Currency currency) {
  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    int scale = minorUnits(currency);
    if (amount.stripTrailingZeros().scale() > scale) throw new IllegalArgumentException("amount has more decimals than " + currency + " allows");
    amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
  }

  public static Money of(String amount, String currency) { return new Money(new BigDecimal(amount), Currency.getInstance(currency)); }

  /** Rounds a computed value to this currency's minor units. */
  public static Money rounded(BigDecimal value, Currency currency) {
    return new Money(value.setScale(minorUnits(currency), RoundingMode.HALF_EVEN), currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public boolean isPositive() { return amount.signum() > 0; }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
  }

  /**
   * Minor units used for amounts. ISO-4217 (and the JDK) list two minor units for IRR, but the rial has no subunit
   * in practice, so Commerce stores and rounds IRR in whole rials (master prompt §12.11).
   */
  private static final Map<String, Integer> MINOR_UNIT_OVERRIDES = Map.of("IRR", 0);

  public static int minorUnits(Currency currency) {
    Integer override = MINOR_UNIT_OVERRIDES.get(currency.getCurrencyCode());
    return override != null ? override : Math.max(currency.getDefaultFractionDigits(), 0);
  }
}
