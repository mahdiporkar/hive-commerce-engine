package io.hivecommerce.ordering.domain;

import io.hivecommerce.ordering.domain.event.OrderingEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A customer's placed purchase (aggregate root) with one {@link SellerOrder} per seller. Invariants: line snapshots are
 * immutable after placement; total = Σ line totals + Σ tax + Σ shipping (one currency); only the transitions below are
 * allowed. Order: PLACED → PAID → COMPLETED; PLACED → CANCELLED; CANCELLED → PAID_UNFULFILLABLE (a capture after the
 * reservation expired: money must be refunded, ADR-0015 change 9).
 */
public final class Order {
  public enum Status { PLACED, PAID, CANCELLED, PAID_UNFULFILLABLE, COMPLETED }

  public enum SellerOrderStatus { AWAITING_PAYMENT, AWAITING_FULFILLMENT, SHIPPED, DELIVERED, CANCELLED }

  /** Immutable snapshot of what was bought, from whom and at what price (master prompt §12 rule 2). */
  public record Line(UUID id, UUID offerId, UUID productId, UUID variantId, String kind, String sku, Map<String, String> title,
      Map<String, Object> variantAttributes, Money unitPrice, int quantity, Money lineTotal, Money tax) {
    public Line {
      title = Map.copyOf(title);
      variantAttributes = Map.copyOf(variantAttributes);
    }

    public boolean physical() { return "PHYSICAL".equals(kind); }
  }

  public record SellerOrder(UUID id, UUID sellerId, SellerOrderStatus status, List<Line> lines, Money shipping) {
    public SellerOrder {
      lines = List.copyOf(lines);
    }

    public Money subtotal() { return lines.stream().map(Line::lineTotal).reduce(Money::plus).orElseThrow(); }

    public boolean hasPhysical() { return lines.stream().anyMatch(Line::physical); }

    SellerOrder with(SellerOrderStatus next) { return new SellerOrder(id, sellerId, next, lines, shipping); }
  }

  /** A line as priced by the checkout, before it is grouped into seller orders. */
  public record LineDraft(UUID sellerId, UUID offerId, UUID productId, UUID variantId, String kind, String sku, Map<String, String> title,
      Map<String, Object> variantAttributes, Money unitPrice, int quantity, Money lineTotal, Money tax) {}

  private final UUID id;
  private final TenantScope scope;
  private final String number;
  private final UUID customerId;
  private final UUID checkoutAttemptId;
  private Status status;
  private final List<SellerOrder> sellerOrders;
  private final Money total;
  private UUID paymentId;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Order(UUID id, TenantScope scope, String number, UUID customerId, UUID checkoutAttemptId, Status status, List<SellerOrder> sellerOrders,
      Money total, UUID paymentId, long revision) {
    this.id = id;
    this.scope = scope;
    this.number = number;
    this.customerId = customerId;
    this.checkoutAttemptId = checkoutAttemptId;
    this.status = status;
    this.sellerOrders = new ArrayList<>(sellerOrders);
    this.total = total;
    this.paymentId = paymentId;
    this.revision = revision;
  }

  /** Places the order, splitting lines into seller orders (the OrderSplitter rule lives here). */
  public static Order place(UUID id, TenantScope scope, String number, UUID customerId, UUID checkoutAttemptId, List<LineDraft> drafts,
      Map<UUID, Money> shippingBySeller, Money expectedTotal, java.util.function.Supplier<UUID> ids, Instant now) {
    if (customerId == null) throw new DomainRuleException("CUSTOMER_REQUIRED", "Orders belong to a signed-in customer");
    if (drafts.isEmpty()) throw new DomainRuleException("ORDER_EMPTY", "An order needs at least one line");
    var bySeller = new LinkedHashMap<UUID, List<Line>>();
    for (var d : drafts) {
      if (d.quantity() < 1) throw new DomainRuleException("QUANTITY_INVALID", "Quantity must be at least 1");
      bySeller.computeIfAbsent(d.sellerId(), k -> new ArrayList<>()).add(new Line(ids.get(), d.offerId(), d.productId(), d.variantId(), d.kind(), d.sku(),
          d.title(), d.variantAttributes(), d.unitPrice(), d.quantity(), d.lineTotal(), d.tax()));
    }
    var currency = expectedTotal.currency();
    var zero = new Money(BigDecimal.ZERO, currency);
    var sellerOrders = new ArrayList<SellerOrder>();
    Money sum = zero;
    for (var entry : bySeller.entrySet()) {
      Money shipping = shippingBySeller.getOrDefault(entry.getKey(), zero);
      var so = new SellerOrder(ids.get(), entry.getKey(), SellerOrderStatus.AWAITING_PAYMENT, entry.getValue(), shipping);
      sum = sum.plus(so.subtotal()).plus(shipping);
      for (var line : so.lines()) sum = sum.plus(line.tax());
      sellerOrders.add(so);
    }
    if (!sum.equals(expectedTotal)) throw new DomainRuleException("ORDER_TOTAL_MISMATCH", "Order total does not equal its lines, tax and shipping");
    var order = new Order(id, scope, number, customerId, checkoutAttemptId, Status.PLACED, sellerOrders, expectedTotal, null, 0);
    order.events.add(new OrderingEvent("OrderPlaced", id.toString(), Map.of("orderId", id.toString(), "number", number, "customerId", customerId.toString(),
        "checkoutAttemptId", checkoutAttemptId.toString(), "total", expectedTotal.amount().toPlainString(), "currency", currency.getCurrencyCode()), now));
    return order;
  }

  public static Order restore(UUID id, TenantScope scope, String number, UUID customerId, UUID checkoutAttemptId, Status status, List<SellerOrder> sellerOrders,
      Money total, UUID paymentId, long revision) {
    return new Order(id, scope, number, customerId, checkoutAttemptId, status, sellerOrders, total, paymentId, revision);
  }

  /** Verified payment arrived. Idempotent; a capture for a cancelled order needs a refund instead of fulfillment. */
  public void markPaid(UUID paymentId, Money amount, Instant now) {
    if (status == Status.PAID || status == Status.COMPLETED || status == Status.PAID_UNFULFILLABLE) return;
    if (!amount.equals(total)) throw new DomainRuleException("PAYMENT_AMOUNT_MISMATCH", "Captured amount differs from the order total");
    this.paymentId = paymentId;
    if (status == Status.CANCELLED) {
      status = Status.PAID_UNFULFILLABLE;
      events.add(new OrderingEvent("OrderPaidUnfulfillable", id.toString(), Map.of("orderId", id.toString(), "paymentId", paymentId.toString(),
          "checkoutAttemptId", checkoutAttemptId.toString(), "amount", amount.amount().toPlainString(), "currency", amount.currency().getCurrencyCode()), now));
      return;
    }
    status = Status.PAID;
    sellerOrders.replaceAll(so -> so.with(SellerOrderStatus.AWAITING_FULFILLMENT));
    events.add(new OrderingEvent("OrderPaid", id.toString(), paidPayload(), now));
  }

  public void cancel(String reason, Instant now) {
    if (status == Status.CANCELLED) return;
    if (status != Status.PLACED) throw new DomainRuleException("ORDER_NOT_CANCELLABLE", "Only unpaid orders can be cancelled here");
    status = Status.CANCELLED;
    sellerOrders.replaceAll(so -> so.with(SellerOrderStatus.CANCELLED));
    events.add(new OrderingEvent("OrderCancelled", id.toString(), Map.of("orderId", id.toString(), "checkoutAttemptId", checkoutAttemptId.toString(),
        "reason", reason == null ? "" : reason), now));
  }

  public void sellerOrderShipped(UUID sellerOrderId, Instant now) {
    transition(sellerOrderId, SellerOrderStatus.AWAITING_FULFILLMENT, SellerOrderStatus.SHIPPED);
  }

  public void sellerOrderDelivered(UUID sellerOrderId, Instant now) {
    var current = sellerOrder(sellerOrderId);
    if (current.status() == SellerOrderStatus.DELIVERED) return;
    if (current.status() != SellerOrderStatus.SHIPPED && current.status() != SellerOrderStatus.AWAITING_FULFILLMENT) {
      throw new DomainRuleException("SELLER_ORDER_STATUS_INVALID", "Cannot deliver a seller order in status " + current.status());
    }
    replace(current.with(SellerOrderStatus.DELIVERED));
    if (status == Status.PAID && sellerOrders.stream().allMatch(so -> so.status() == SellerOrderStatus.DELIVERED)) {
      status = Status.COMPLETED;
      events.add(new OrderingEvent("OrderCompleted", id.toString(), Map.of("orderId", id.toString()), now));
    }
  }

  private void transition(UUID sellerOrderId, SellerOrderStatus from, SellerOrderStatus to) {
    var current = sellerOrder(sellerOrderId);
    if (current.status() == to) return;
    if (current.status() != from) throw new DomainRuleException("SELLER_ORDER_STATUS_INVALID", "Cannot move a seller order from " + current.status() + " to " + to);
    replace(current.with(to));
  }

  private SellerOrder sellerOrder(UUID id) {
    return sellerOrders.stream().filter(so -> so.id().equals(id)).findFirst()
        .orElseThrow(() -> new DomainRuleException("SELLER_ORDER_UNKNOWN", "Unknown seller order"));
  }

  private void replace(SellerOrder updated) {
    sellerOrders.replaceAll(so -> so.id().equals(updated.id()) ? updated : so);
  }

  private Map<String, Object> paidPayload() {
    var lines = new ArrayList<Map<String, Object>>();
    var sellers = new ArrayList<Map<String, Object>>();
    for (var so : sellerOrders) {
      sellers.add(Map.of("sellerOrderId", so.id().toString(), "sellerId", so.sellerId().toString(), "subtotal", so.subtotal().amount().toPlainString(),
          "shipping", so.shipping().amount().toPlainString(), "hasPhysical", so.hasPhysical()));
      for (var l : so.lines()) {
        lines.add(Map.of("lineId", l.id().toString(), "sellerOrderId", so.id().toString(), "sellerId", so.sellerId().toString(), "offerId", l.offerId().toString(),
            "productId", l.productId().toString(), "variantId", l.variantId().toString(), "kind", l.kind(), "quantity", l.quantity(),
            "lineTotal", l.lineTotal().amount().toPlainString(), "tax", l.tax().amount().toPlainString()));
      }
    }
    return Map.of("orderId", id.toString(), "number", number, "customerId", customerId.toString(), "checkoutAttemptId", checkoutAttemptId.toString(),
        "paymentId", paymentId.toString(), "currency", total.currency().getCurrencyCode(), "total", total.amount().toPlainString(), "lines", lines,
        "sellerOrders", sellers);
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public String number() { return number; }
  public UUID customerId() { return customerId; }
  public UUID checkoutAttemptId() { return checkoutAttemptId; }
  public Status status() { return status; }
  public List<SellerOrder> sellerOrders() { return List.copyOf(sellerOrders); }
  public Money total() { return total; }
  public UUID paymentId() { return paymentId; }
  public long revision() { return revision; }
}
