package io.hivecommerce.inventory.application.published;

import io.hivecommerce.shared.TenantScope;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Inventory's published in-process API, used synchronously by the checkout saga and by storefront reads. */
public interface InventoryApi {
  record ReserveLine(UUID offerId, int quantity) {}

  /**
   * Reserves every line for the checkout attempt, all or nothing, in one Inventory transaction (a documented exception to
   * one-aggregate-per-transaction: a multi-line reservation must be atomic). Idempotent per attempt.
   *
   * @throws io.hivecommerce.shared.DomainRuleException {@code OUT_OF_STOCK} when any line cannot be reserved
   */
  void reserve(TenantScope scope, UUID checkoutAttemptId, List<ReserveLine> lines, Duration ttl);

  void release(TenantScope scope, UUID checkoutAttemptId);

  /** Units available now per offer; offers without stock records are absent. */
  Map<UUID, Integer> availability(TenantScope scope, Collection<UUID> offerIds);
}
