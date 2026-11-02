package io.hivecommerce.pricing.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain service that computes a quote from server-side unit prices (master prompt §12 rule 1: client amounts are
 * ignored). Tax is a percentage per line, rounded per currency; shipping is a flat amount per seller order that has
 * physical lines. One currency per quote; mixing currencies is refused.
 */
public final class PriceCalculator {
  public record Line(String ref, UUID sellerId, boolean physical, Money unitPrice, int quantity) {}

  public record QuotedLine(String ref, UUID sellerId, Money unitPrice, int quantity, Money lineTotal, Money tax) {}

  public record Quote(List<QuotedLine> lines, Map<UUID, Money> shippingBySeller, Money subtotal, Money tax, Money shipping, Money total) {}

  public record Policy(BigDecimal taxRatePercent, BigDecimal flatShippingPerSellerOrder) {
    public Policy {
      if (taxRatePercent.signum() < 0 || taxRatePercent.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("tax rate must be 0..100");
      if (flatShippingPerSellerOrder.signum() < 0) throw new IllegalArgumentException("shipping must not be negative");
    }
  }

  private PriceCalculator() {}

  public static Quote quote(List<Line> lines, Policy policy) {
    if (lines.isEmpty()) throw new DomainRuleException("CART_EMPTY", "Nothing to price");
    var currency = lines.getFirst().unitPrice().currency();
    var zero = new Money(BigDecimal.ZERO, currency);
    var quoted = new ArrayList<QuotedLine>();
    var shipping = new LinkedHashMap<UUID, Money>();
    Money subtotal = zero, tax = zero;
    for (Line line : lines) {
      if (!line.unitPrice().currency().equals(currency)) throw new DomainRuleException("CURRENCY_MISMATCH", "All items must be priced in " + currency);
      if (line.quantity() < 1) throw new DomainRuleException("QUANTITY_INVALID", "Quantity must be at least 1");
      Money lineTotal = Money.rounded(line.unitPrice().amount().multiply(BigDecimal.valueOf(line.quantity())), currency);
      Money lineTax = Money.rounded(lineTotal.amount().multiply(policy.taxRatePercent()).movePointLeft(2), currency);
      quoted.add(new QuotedLine(line.ref(), line.sellerId(), line.unitPrice(), line.quantity(), lineTotal, lineTax));
      subtotal = subtotal.plus(lineTotal);
      tax = tax.plus(lineTax);
      if (line.physical()) shipping.putIfAbsent(line.sellerId(), Money.rounded(policy.flatShippingPerSellerOrder(), currency));
      else shipping.putIfAbsent(line.sellerId(), zero);
      if (line.physical() && shipping.get(line.sellerId()).amount().signum() == 0) shipping.put(line.sellerId(), Money.rounded(policy.flatShippingPerSellerOrder(), currency));
    }
    Money shippingTotal = shipping.values().stream().reduce(zero, Money::plus);
    return new Quote(quoted, shipping, subtotal, tax, shippingTotal, subtotal.plus(tax).plus(shippingTotal));
  }
}
