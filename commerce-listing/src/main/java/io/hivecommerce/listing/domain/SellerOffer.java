package io.hivecommerce.listing.domain;

import io.hivecommerce.listing.domain.event.OfferEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One seller's commercial terms for a variant (aggregate root). Invariants: price &gt; 0 in the store currency; the
 * seller is active when the offer is made; a unique item offers at most one unit; digital offers are unlimited.
 * Price, stock and condition are first-class here, never product attributes (master prompt §10).
 */
public final class SellerOffer {
  public enum Condition { NEW, USED, REFURBISHED }

  public enum Status { ACTIVE, WITHDRAWN }

  private static final Pattern SELLER_SKU = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final UUID id;
  private final TenantScope scope;
  private final UUID sellerId;
  private final UUID productId;
  private final UUID variantId;
  private Money price;
  private final Condition condition;
  private final boolean uniqueItem;
  private final boolean digital;
  private final String sellerSku;
  private Status status;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private SellerOffer(UUID id, TenantScope scope, UUID sellerId, UUID productId, UUID variantId, Money price, Condition condition, boolean uniqueItem,
      boolean digital, String sellerSku, Status status, long revision) {
    this.id = id;
    this.scope = scope;
    this.sellerId = sellerId;
    this.productId = productId;
    this.variantId = variantId;
    this.price = price;
    this.condition = condition;
    this.uniqueItem = uniqueItem;
    this.digital = digital;
    this.sellerSku = sellerSku;
    this.status = status;
    this.revision = revision;
  }

  public static SellerOffer create(UUID id, TenantScope scope, UUID sellerId, boolean sellerActive, UUID productId, UUID variantId, Money price,
      String storeCurrency, Condition condition, boolean uniqueItem, boolean digital, Integer quantity, String sellerSku, Instant now) {
    if (!sellerActive) throw new DomainRuleException("SELLER_NOT_ACTIVE", "Only active sellers can make offers");
    var v = new io.hivecommerce.shared.Violations();
    if (price == null || !price.isPositive()) v.add("price", "must be greater than zero");
    else if (!price.currency().getCurrencyCode().equals(storeCurrency)) v.add("price.currency", "must be the store currency " + storeCurrency);
    if (digital && uniqueItem) v.add("uniqueItem", "digital items are not unique items");
    if (!digital && (quantity == null || quantity < 0)) v.add("quantity", "is required for physical items and must not be negative");
    if (uniqueItem && quantity != null && quantity > 1) v.add("quantity", "a unique item has at most one unit");
    if (sellerSku != null && !SELLER_SKU.matcher(sellerSku).matches()) v.add("sellerSku", "invalid seller SKU");
    v.throwIfAny();
    var offer = new SellerOffer(id, scope, sellerId, productId, variantId, price, condition == null ? Condition.NEW : condition, uniqueItem, digital, sellerSku,
        Status.ACTIVE, 0);
    var payload = new HashMap<String, Object>();
    payload.put("offerId", id.toString());
    payload.put("sellerId", sellerId.toString());
    payload.put("productId", productId.toString());
    payload.put("variantId", variantId.toString());
    payload.put("unique", uniqueItem);
    payload.put("unlimited", digital);
    payload.put("initialQuantity", digital ? 0 : quantity);
    offer.events.add(new OfferEvent("OfferCreated", id, payload, now));
    return offer;
  }

  public static SellerOffer restore(UUID id, TenantScope scope, UUID sellerId, UUID productId, UUID variantId, Money price, Condition condition,
      boolean uniqueItem, boolean digital, String sellerSku, Status status, long revision) {
    return new SellerOffer(id, scope, sellerId, productId, variantId, price, condition, uniqueItem, digital, sellerSku, status, revision);
  }

  public void changePrice(Money newPrice, Instant now) {
    if (status != Status.ACTIVE) throw new DomainRuleException("OFFER_WITHDRAWN", "The offer is withdrawn");
    if (newPrice == null || !newPrice.isPositive()) throw DomainValidationException.of("price", "must be greater than zero");
    if (!newPrice.currency().equals(price.currency())) throw DomainValidationException.of("price.currency", "must stay " + price.currency());
    price = newPrice;
    events.add(new OfferEvent("OfferPriceChanged", id, java.util.Map.of("offerId", id.toString(), "amount", newPrice.amount().toPlainString()), now));
  }

  public void withdraw(Instant now) {
    if (status == Status.WITHDRAWN) return;
    status = Status.WITHDRAWN;
    events.add(new OfferEvent("OfferWithdrawn", id, java.util.Map.of("offerId", id.toString()), now));
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID sellerId() { return sellerId; }
  public UUID productId() { return productId; }
  public UUID variantId() { return variantId; }
  public Money price() { return price; }
  public Condition condition() { return condition; }
  public boolean uniqueItem() { return uniqueItem; }
  public boolean digital() { return digital; }
  public String sellerSku() { return sellerSku; }
  public Status status() { return status; }
  public long revision() { return revision; }
}
