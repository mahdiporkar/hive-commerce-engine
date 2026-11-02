package io.hivecommerce.inventory.domain;

import io.hivecommerce.inventory.domain.event.InventoryEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock position of one seller offer (aggregate root). Invariants: {@code available = onHand - reserved >= 0};
 * a unique item never has more than one unit; every reservation has a TTL and is released, consumed or expired exactly
 * once. Unlimited items (digital goods) record reservations but never run out. Concurrency: the repository persists
 * with optimistic {@code revision}, so two buyers racing for the last unit cannot both reserve it.
 */
public final class StockItem {
  public enum ReservationStatus { RESERVED, RELEASED, CONSUMED, EXPIRED }

  public record Reservation(UUID id, UUID checkoutAttemptId, int quantity, Instant expiresAt, ReservationStatus status) {}

  private final UUID id;
  private final TenantScope scope;
  private final UUID offerId;
  private final UUID sellerId;
  private final boolean unique;
  private final boolean unlimited;
  private int onHand;
  private int reserved;
  private final List<Reservation> reservations;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private StockItem(UUID id, TenantScope scope, UUID offerId, UUID sellerId, boolean unique, boolean unlimited, int onHand, int reserved,
      List<Reservation> reservations, long revision) {
    this.id = id;
    this.scope = scope;
    this.offerId = offerId;
    this.sellerId = sellerId;
    this.unique = unique;
    this.unlimited = unlimited;
    this.onHand = onHand;
    this.reserved = reserved;
    this.reservations = new ArrayList<>(reservations);
    this.revision = revision;
  }

  public static StockItem open(UUID id, TenantScope scope, UUID offerId, UUID sellerId, int quantity, boolean unique, boolean unlimited, Instant now) {
    if (quantity < 0) throw DomainValidationException.of("quantity", "must not be negative");
    if (unique && quantity > 1) throw DomainValidationException.of("quantity", "a unique item has at most one unit");
    var item = new StockItem(id, scope, offerId, sellerId, unique, unlimited, unlimited ? 0 : quantity, 0, List.of(), 0);
    item.events.add(new InventoryEvent("StockOpened", id.toString(), Map.of("offerId", offerId.toString(), "quantity", quantity), now));
    return item;
  }

  public static StockItem restore(UUID id, TenantScope scope, UUID offerId, UUID sellerId, boolean unique, boolean unlimited, int onHand, int reserved,
      List<Reservation> reservations, long revision) {
    return new StockItem(id, scope, offerId, sellerId, unique, unlimited, onHand, reserved, reservations, revision);
  }

  public int available() { return unlimited ? Integer.MAX_VALUE : onHand - reserved; }

  public void reserve(UUID reservationId, UUID checkoutAttemptId, int quantity, Instant expiresAt, Instant now) {
    if (quantity < 1) throw DomainValidationException.of("quantity", "must be at least 1");
    if (unique && quantity > 1) throw DomainValidationException.of("quantity", "a unique item can be bought only once");
    if (reservations.stream().anyMatch(r -> r.checkoutAttemptId().equals(checkoutAttemptId) && r.status() == ReservationStatus.RESERVED)) return;
    if (!unlimited && available() < quantity) throw new DomainRuleException("OUT_OF_STOCK", "Not enough stock for this item");
    reservations.add(new Reservation(reservationId, checkoutAttemptId, quantity, expiresAt, ReservationStatus.RESERVED));
    if (!unlimited) reserved += quantity;
    events.add(new InventoryEvent("StockReserved", id.toString(),
        Map.of("offerId", offerId.toString(), "checkoutAttemptId", checkoutAttemptId.toString(), "quantity", quantity, "expiresAt", expiresAt.toString()), now));
  }

  /** Releases the attempt's active reservation (exactly once; later calls are no-ops). */
  public boolean release(UUID checkoutAttemptId, Instant now) {
    return close(checkoutAttemptId, ReservationStatus.RELEASED, now);
  }

  /** Turns the attempt's reservation into a sale: stock leaves the shelf. */
  public boolean consume(UUID checkoutAttemptId, Instant now) {
    return close(checkoutAttemptId, ReservationStatus.CONSUMED, now);
  }

  /** Expires reservations past their TTL; returns the checkout attempts affected. */
  public List<UUID> expire(Instant now) {
    var expired = reservations.stream().filter(r -> r.status() == ReservationStatus.RESERVED && !r.expiresAt().isAfter(now)).map(Reservation::checkoutAttemptId).toList();
    expired.forEach(attempt -> close(attempt, ReservationStatus.EXPIRED, now));
    return expired;
  }

  public void adjustOnHand(int quantity, Instant now) {
    if (unlimited) throw new DomainRuleException("STOCK_UNLIMITED", "Unlimited items have no stock level");
    if (quantity < reserved) throw new DomainRuleException("STOCK_BELOW_RESERVED", "Stock cannot drop below reserved units (" + reserved + ")");
    if (unique && quantity > 1) throw DomainValidationException.of("quantity", "a unique item has at most one unit");
    onHand = quantity;
    events.add(new InventoryEvent("StockAdjusted", id.toString(), Map.of("offerId", offerId.toString(), "onHand", quantity), now));
  }

  private boolean close(UUID checkoutAttemptId, ReservationStatus target, Instant now) {
    for (int i = 0; i < reservations.size(); i++) {
      var r = reservations.get(i);
      if (!r.checkoutAttemptId().equals(checkoutAttemptId) || r.status() != ReservationStatus.RESERVED) continue;
      reservations.set(i, new Reservation(r.id(), r.checkoutAttemptId(), r.quantity(), r.expiresAt(), target));
      if (!unlimited) {
        reserved -= r.quantity();
        if (target == ReservationStatus.CONSUMED) onHand -= r.quantity();
      }
      String name = switch (target) { case RELEASED -> "ReservationReleased"; case CONSUMED -> "StockConsumed"; case EXPIRED -> "ReservationExpired"; default -> "Reservation" + target; };
      events.add(new InventoryEvent(name, id.toString(), Map.of("offerId", offerId.toString(), "checkoutAttemptId", checkoutAttemptId.toString(), "quantity", r.quantity()), now));
      return true;
    }
    return false;
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID offerId() { return offerId; }
  public UUID sellerId() { return sellerId; }
  public boolean unique() { return unique; }
  public boolean unlimited() { return unlimited; }
  public int onHand() { return onHand; }
  public int reserved() { return reserved; }
  public List<Reservation> reservations() { return List.copyOf(reservations); }
  public long revision() { return revision; }
}
