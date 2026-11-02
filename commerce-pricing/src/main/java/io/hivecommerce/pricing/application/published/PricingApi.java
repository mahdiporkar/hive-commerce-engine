package io.hivecommerce.pricing.application.published;

import io.hivecommerce.shared.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pricing's published API: a quote computed from server-side prices and the store's tax/shipping policy. */
public interface PricingApi {
  record Line(String ref, UUID sellerId, boolean physical, Money unitPrice, int quantity) {}

  record QuotedLine(String ref, UUID sellerId, Money unitPrice, int quantity, Money lineTotal, Money tax) {}

  record Quote(List<QuotedLine> lines, Map<UUID, Money> shippingBySeller, Money subtotal, Money tax, Money shipping, Money total) {}

  Quote quote(List<Line> lines);
}
