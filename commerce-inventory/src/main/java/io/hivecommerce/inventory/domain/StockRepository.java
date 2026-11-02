package io.hivecommerce.inventory.domain;

import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockRepository {
  /** Inserts unless a stock item for the offer exists (event redelivery is a no-op). */
  void insertIfAbsent(StockItem item);

  /** Persists counters and reservations; throws OptimisticLockingFailureException on a stale revision. */
  void update(StockItem item);

  Optional<StockItem> findByOffer(TenantScope scope, UUID offerId);

  List<StockItem> findByOffers(TenantScope scope, Collection<UUID> offerIds);

  List<StockItem> findByAttempt(TenantScope scope, UUID checkoutAttemptId);

  List<StockItem> findWithExpiredReservations(TenantScope scope, Instant now);
}
