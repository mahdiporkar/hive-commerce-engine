package io.hivecommerce.inventory.application;

import io.hivecommerce.inventory.application.published.InventoryApi;
import io.hivecommerce.inventory.domain.StockItem;
import io.hivecommerce.inventory.domain.StockRepository;
import io.hivecommerce.inventory.infrastructure.InventoryOutbox;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Inventory use cases, event consumers and the reservation-expiry job. */
@Service
public class InventoryService implements InventoryApi {
  private static final int RETRIES = 5;
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final StockRepository stock;
  private final InventoryOutbox outbox;
  private final Clock clock;

  public InventoryService(DeploymentScope deployment, TenantTransactions transactions, StockRepository stock, InventoryOutbox outbox, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.stock = stock;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Override
  public void reserve(TenantScope scope, UUID attemptId, List<ReserveLine> lines, Duration ttl) {
    retrying(() -> transactions.inScope(scope, () -> {
      var items = stock.findByOffers(scope, lines.stream().map(ReserveLine::offerId).toList());
      var byOffer = new HashMap<UUID, StockItem>();
      items.forEach(i -> byOffer.put(i.offerId(), i));
      var expiresAt = clock.instant().plus(ttl);
      for (var line : lines) {
        var item = byOffer.get(line.offerId());
        if (item == null) throw new DomainRuleException("OUT_OF_STOCK", "Not enough stock for this item");
        item.reserve(Ids.newId(), attemptId, line.quantity(), expiresAt, clock.instant());
      }
      for (var item : byOffer.values()) save(scope, item);
      return null;
    }));
  }

  @Override
  public void release(TenantScope scope, UUID attemptId) {
    retrying(() -> transactions.inScope(scope, () -> {
      for (var item : stock.findByAttempt(scope, attemptId)) if (item.release(attemptId, clock.instant())) save(scope, item);
      return null;
    }));
  }

  void consume(TenantScope scope, UUID attemptId) {
    retrying(() -> transactions.inScope(scope, () -> {
      for (var item : stock.findByAttempt(scope, attemptId)) if (item.consume(attemptId, clock.instant())) save(scope, item);
      return null;
    }));
  }

  @Override
  public Map<UUID, Integer> availability(TenantScope scope, Collection<UUID> offerIds) {
    var result = new HashMap<UUID, Integer>();
    if (offerIds.isEmpty()) return result;
    for (var item : stock.findByOffers(scope, offerIds)) result.put(item.offerId(), item.available());
    return result;
  }

  public record StockView(UUID offerId, int onHand, int reserved, int available, boolean unique, boolean unlimited, long revision) {
    static StockView of(StockItem i) { return new StockView(i.offerId(), i.onHand(), i.reserved(), i.unlimited() ? -1 : i.available(), i.unique(), i.unlimited(), i.revision()); }
  }

  /** Operator restock / correction (Layer 1: commerce.api.catalog:update). */
  public StockView adjust(UserContext user, UUID offerId, int onHand, long revision) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var item = stock.findByOffer(scope, offerId).orElseThrow(() -> HiveException.notFound("No stock for this offer"));
      if (item.revision() != revision) throw HiveException.stale();
      item.adjustOnHand(onHand, clock.instant());
      save(scope, item);
      return StockView.of(stock.findByOffer(scope, offerId).orElseThrow());
    });
  }

  /** Releases reservations whose TTL passed; emits ReservationExpired so the checkout and order are compensated. */
  @Scheduled(fixedDelayString = "${commerce.inventory.expiry-interval-ms:30000}", initialDelayString = "${commerce.inventory.expiry-interval-ms:30000}")
  public void expireReservations() {
    TenantScope scope = deployment.scope();
    retrying(() -> transactions.inScope(scope, () -> {
      for (var item : stock.findWithExpiredReservations(scope, clock.instant())) if (!item.expire(clock.instant()).isEmpty()) save(scope, item);
      return null;
    }));
  }

  private void save(TenantScope scope, StockItem item) {
    stock.update(item);
    outbox.append(scope, item.pullEvents());
  }

  private static void retrying(Supplier<?> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        work.get();
        return;
      } catch (OptimisticLockingFailureException e) {
        if (attempt >= RETRIES) throw new DomainRuleException("STOCK_BUSY", "Stock is changing; try again");
      }
    }
  }

  /** Listing → Inventory: every new offer gets a stock item (context map). */
  @org.springframework.stereotype.Component
  static class OfferCreatedHandler implements EventHandler {
    private final StockRepository stock;
    private final InventoryOutbox outbox;
    private final Clock clock;

    OfferCreatedHandler(StockRepository stock, InventoryOutbox outbox, Clock clock) {
      this.stock = stock;
      this.outbox = outbox;
      this.clock = clock;
    }

    public String consumer() { return "inventory.offer-created"; }
    public Set<String> eventTypes() { return Set.of("commerce.listing.OfferCreated"); }

    public void handle(EventEnvelope e) {
      var p = e.payload();
      var scope = new TenantScope(e.tenantId(), e.storeId());
      var item = StockItem.open(Ids.newId(), scope, UUID.fromString((String) p.get("offerId")), UUID.fromString((String) p.get("sellerId")),
          ((Number) p.get("initialQuantity")).intValue(), Boolean.TRUE.equals(p.get("unique")), Boolean.TRUE.equals(p.get("unlimited")), clock.instant());
      stock.insertIfAbsent(item);
      outbox.append(scope, item.pullEvents());
    }
  }

  /** Ordering → Inventory: a paid order consumes its reservations; a cancelled one releases them. */
  @org.springframework.stereotype.Component
  static class OrderOutcomeHandler implements EventHandler {
    private final InventoryService inventory;

    OrderOutcomeHandler(InventoryService inventory) {
      this.inventory = inventory;
    }

    public String consumer() { return "inventory.order-outcome"; }
    public Set<String> eventTypes() { return Set.of("commerce.ordering.OrderPaid", "commerce.ordering.OrderCancelled"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      UUID attempt = UUID.fromString((String) e.payload().get("checkoutAttemptId"));
      if (e.type().endsWith("OrderPaid")) inventory.consume(scope, attempt);
      else inventory.release(scope, attempt);
    }
  }
}
