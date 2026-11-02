package io.hivecommerce.payment.domain;

import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  void insert(Payment payment);

  void update(Payment payment);

  Optional<Payment> findById(TenantScope scope, UUID id);

  Optional<Payment> findByAttempt(TenantScope scope, UUID checkoutAttemptId);

  Optional<Payment> findByProviderRef(TenantScope scope, String provider, String providerRef);

  Optional<Payment> findByOrder(TenantScope scope, UUID orderId);

  List<Payment> findInitiatedBefore(TenantScope scope, Instant before);

  /** Records a provider callback once; returns false when (provider, eventId) was already received (replay). */
  boolean recordWebhook(TenantScope scope, String provider, String eventId, String providerRef, String body);
}
