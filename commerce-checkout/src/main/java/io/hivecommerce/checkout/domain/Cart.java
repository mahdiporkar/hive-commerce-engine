package io.hivecommerce.checkout.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lines a visitor or customer intends to buy (aggregate root). Invariants: lines reference offers by id only; quantity
 * 1..99 and exactly 1 for unique items; at most 100 lines; a guest cart is identified by the hash of its token only.
 */
public final class Cart {
  public enum Status { ACTIVE, MERGED, CHECKED_OUT }

  public record Item(UUID id, UUID offerId, int quantity, boolean uniqueItem) {}

  private static final int MAX_LINES = 100, MAX_QUANTITY = 99;

  private final UUID id;
  private final TenantScope scope;
  private UUID customerId;
  private final String guestTokenHash;
  private Status status;
  private final List<Item> items;
  private final Instant expiresAt;
  private final long revision;

  private Cart(UUID id, TenantScope scope, UUID customerId, String guestTokenHash, Status status, List<Item> items, Instant expiresAt, long revision) {
    this.id = id;
    this.scope = scope;
    this.customerId = customerId;
    this.guestTokenHash = guestTokenHash;
    this.status = status;
    this.items = new ArrayList<>(items);
    this.expiresAt = expiresAt;
    this.revision = revision;
  }

  public static Cart forGuest(UUID id, TenantScope scope, String tokenHash, Instant expiresAt) {
    return new Cart(id, scope, null, tokenHash, Status.ACTIVE, List.of(), expiresAt, 0);
  }

  public static Cart forCustomer(UUID id, TenantScope scope, UUID customerId) {
    return new Cart(id, scope, customerId, null, Status.ACTIVE, List.of(), null, 0);
  }

  public static Cart restore(UUID id, TenantScope scope, UUID customerId, String tokenHash, Status status, List<Item> items, Instant expiresAt, long revision) {
    return new Cart(id, scope, customerId, tokenHash, status, items, expiresAt, revision);
  }

  public void add(UUID itemId, UUID offerId, int quantity, boolean uniqueItem) {
    requireActive();
    if (quantity < 1 || quantity > MAX_QUANTITY) throw DomainValidationException.of("quantity", "must be between 1 and " + MAX_QUANTITY);
    for (int i = 0; i < items.size(); i++) {
      var existing = items.get(i);
      if (!existing.offerId().equals(offerId)) continue;
      int merged = uniqueItem ? 1 : Math.min(MAX_QUANTITY, existing.quantity() + quantity);
      items.set(i, new Item(existing.id(), offerId, merged, uniqueItem));
      return;
    }
    if (uniqueItem && quantity > 1) throw DomainValidationException.of("quantity", "a unique item can be bought only once");
    if (items.size() >= MAX_LINES) throw new DomainRuleException("CART_FULL", "A cart holds at most " + MAX_LINES + " lines");
    items.add(new Item(itemId, offerId, quantity, uniqueItem));
  }

  public void remove(UUID itemId) {
    requireActive();
    if (!items.removeIf(i -> i.id().equals(itemId))) throw new DomainRuleException("CART_ITEM_UNKNOWN", "No such cart line");
  }

  /** Moves a guest cart's lines into this customer cart at sign-in (unique items deduplicated); the guest cart is closed. */
  public void absorb(Cart guest, java.util.function.Supplier<UUID> ids) {
    requireActive();
    if (guest.status != Status.ACTIVE || guest.customerId != null) throw new DomainRuleException("CART_NOT_MERGEABLE", "Only active guest carts can be merged");
    for (var item : guest.items) {
      if (items.size() >= MAX_LINES) break;
      add(ids.get(), item.offerId(), item.quantity(), item.uniqueItem());
    }
    guest.items.clear();
    guest.status = Status.MERGED;
  }

  public void checkedOut() {
    requireActive();
    status = Status.CHECKED_OUT;
  }

  public boolean expired(Instant now) { return expiresAt != null && !expiresAt.isAfter(now); }

  private void requireActive() {
    if (status != Status.ACTIVE) throw new DomainRuleException("CART_CLOSED", "This cart is no longer active");
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID customerId() { return customerId; }
  public String guestTokenHash() { return guestTokenHash; }
  public Status status() { return status; }
  public List<Item> items() { return List.copyOf(items); }
  public Instant expiresAt() { return expiresAt; }
  public long revision() { return revision; }
}
