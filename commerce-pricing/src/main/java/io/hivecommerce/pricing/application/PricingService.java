package io.hivecommerce.pricing.application;

import io.hivecommerce.pricing.application.published.PricingApi;
import io.hivecommerce.pricing.domain.PriceCalculator;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Store pricing policy from configuration ({@code commerce.pricing.*}). Promotions, coupons and price lists are later
 * work; tax and shipping are ports so client repositories can plug real calculators.
 */
@Service
class PricingService implements PricingApi {
  private final PriceCalculator.Policy policy;

  PricingService(@Value("${commerce.pricing.tax-rate-percent:0}") BigDecimal taxRate, @Value("${commerce.pricing.flat-shipping:0}") BigDecimal shipping) {
    this.policy = new PriceCalculator.Policy(taxRate, shipping);
  }

  @Override
  public Quote quote(List<Line> lines) {
    var q = PriceCalculator.quote(lines.stream().map(l -> new PriceCalculator.Line(l.ref(), l.sellerId(), l.physical(), l.unitPrice(), l.quantity())).toList(), policy);
    return new Quote(q.lines().stream().map(l -> new QuotedLine(l.ref(), l.sellerId(), l.unitPrice(), l.quantity(), l.lineTotal(), l.tax())).toList(),
        q.shippingBySeller(), q.subtotal(), q.tax(), q.shipping(), q.total());
  }
}
