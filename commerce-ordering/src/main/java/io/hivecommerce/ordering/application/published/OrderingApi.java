package io.hivecommerce.ordering.application.published;

import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ordering's published API, used by the checkout saga (synchronous step) and by other contexts' reads. */
public interface OrderingApi {
  /** A priced line to be placed (the order stores it as an immutable snapshot). */
  record LineDraft(UUID sellerId, UUID offerId, UUID productId, UUID variantId, String kind, String sku, Map<String, String> title,
      Map<String, Object> variantAttributes, Money unitPrice, int quantity, Money lineTotal, Money tax) {}

  record PlacedOrder(UUID orderId, String number, Money total) {}

  record OrderSummary(UUID orderId, String number, UUID customerId, String status, Money total) {}

  /** Idempotent per checkout attempt: placing twice returns the existing order. */
  PlacedOrder placeOrder(TenantScope scope, UUID customerId, UUID checkoutAttemptId, List<LineDraft> lines, Map<UUID, Money> shippingBySeller,
      Money total);

  void cancelUnpaid(TenantScope scope, UUID orderId, String reason);

  Optional<OrderSummary> order(TenantScope scope, UUID orderId);
}
