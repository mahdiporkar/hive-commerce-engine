package io.hivecommerce.checkout.domain;

import io.hivecommerce.shared.TenantScope;
import java.util.Optional;
import java.util.UUID;

public final class CheckoutRepositories {
  private CheckoutRepositories() {}

  public interface Carts {
    void insert(Cart cart);

    void update(Cart cart);

    Optional<Cart> findById(TenantScope scope, UUID id);

    Optional<Cart> findActiveByCustomer(TenantScope scope, UUID customerId);

    Optional<Cart> findActiveByTokenHash(TenantScope scope, String tokenHash);
  }

  public interface Attempts {
    void insert(CheckoutAttempt attempt);

    void update(CheckoutAttempt attempt);

    Optional<CheckoutAttempt> findById(TenantScope scope, UUID id);

    Optional<CheckoutAttempt> findByKey(TenantScope scope, UUID customerId, String idempotencyKey);
  }
}
