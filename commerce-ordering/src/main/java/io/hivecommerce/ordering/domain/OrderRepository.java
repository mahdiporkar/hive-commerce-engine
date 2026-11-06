package io.hivecommerce.ordering.domain;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
  void insert(Order order);

  /** Persists order and seller-order statuses; lines are immutable and never rewritten. */
  void update(Order order);

  Optional<Order> findById(TenantScope scope, UUID id);

  Optional<Order> findByAttempt(TenantScope scope, UUID checkoutAttemptId);

  List<Order> findByCustomer(TenantScope scope, UUID customerId, int limit);

  List<Order> findRecent(TenantScope scope, int limit);

  /** True when the customer paid for this product and that line has not been refunded. */
  boolean hasPaidFor(TenantScope scope, UUID customerId, UUID productId);

  record CustomerSummary(UUID customerId, long orders, java.time.Instant lastOrderAt) {}

  List<CustomerSummary> customers(TenantScope scope, int limit);

  List<Order> findBySeller(TenantScope scope, UUID sellerId, int limit);

  Optional<Order> findBySellerOrder(TenantScope scope, UUID sellerOrderId);
}
