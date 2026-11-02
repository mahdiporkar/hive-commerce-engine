package io.hivecommerce.payment.application.published;

import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.UUID;

/** Payment's published API, used by the checkout saga. */
public interface PaymentApi {
  record PaymentStart(UUID paymentId, String redirectUrl) {}

  /** Idempotent per checkout attempt. */
  PaymentStart initiate(TenantScope scope, UUID orderId, UUID checkoutAttemptId, UUID customerId, Money amount);
}
