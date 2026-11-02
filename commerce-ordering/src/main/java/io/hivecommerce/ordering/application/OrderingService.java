package io.hivecommerce.ordering.application;

import io.hivecommerce.ordering.application.published.OrderingApi;
import io.hivecommerce.ordering.domain.Order;
import io.hivecommerce.ordering.domain.OrderRepository;
import io.hivecommerce.ordering.infrastructure.OrderingOutbox;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** Ordering use cases, customer and operator reads, and the event consumers that move orders through their states. */
@Service
public class OrderingService implements OrderingApi {
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final OrderRepository orders;
  private final OrderingOutbox outbox;
  private final Clock clock;

  public OrderingService(DeploymentScope deployment, TenantTransactions transactions, OrderRepository orders, OrderingOutbox outbox, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.orders = orders;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Override
  public PlacedOrder placeOrder(TenantScope scope, UUID customerId, UUID attemptId, List<LineDraft> published, Map<UUID, Money> shipping, Money total) {
    var lines = published.stream().map(d -> new Order.LineDraft(d.sellerId(), d.offerId(), d.productId(), d.variantId(), d.kind(), d.sku(), d.title(),
        d.variantAttributes(), d.unitPrice(), d.quantity(), d.lineTotal(), d.tax())).toList();
    return transactions.inScope(scope, () -> {
      var existing = orders.findByAttempt(scope, attemptId);
      if (existing.isPresent()) return new PlacedOrder(existing.get().id(), existing.get().number(), existing.get().total());
      var order = Order.place(Ids.newId(), scope, number(), customerId, attemptId, lines, shipping, total, Ids::newId, clock.instant());
      orders.insert(order);
      outbox.append(scope, order.pullEvents());
      return new PlacedOrder(order.id(), order.number(), order.total());
    });
  }

  @Override
  public void cancelUnpaid(TenantScope scope, UUID orderId, String reason) {
    transactions.inScope(scope, () -> {
      orders.findById(scope, orderId).ifPresent(order -> {
        if (order.status() != Order.Status.PLACED) return;
        order.cancel(reason, clock.instant());
        save(scope, order);
      });
      return null;
    });
  }

  @Override
  public Optional<OrderSummary> order(TenantScope scope, UUID orderId) {
    return orders.findById(scope, orderId).map(o -> new OrderSummary(o.id(), o.number(), o.customerId(), o.status().name(), o.total()));
  }

  // ---- reads ----

  public record LineView(UUID lineId, UUID offerId, UUID productId, String kind, String sku, Map<String, String> title, Map<String, Object> variantAttributes,
      BigDecimal unitPrice, int quantity, BigDecimal lineTotal, BigDecimal tax) {}

  public record SellerOrderView(UUID sellerOrderId, UUID sellerId, String status, BigDecimal shipping, List<LineView> lines) {}

  public record OrderView(UUID orderId, String number, UUID customerId, String status, BigDecimal total, String currency, UUID paymentId,
      List<SellerOrderView> sellerOrders) {
    static OrderView of(Order o) {
      return new OrderView(o.id(), o.number(), o.customerId(), o.status().name(), o.total().amount(), o.total().currency().getCurrencyCode(), o.paymentId(),
          o.sellerOrders().stream().map(so -> new SellerOrderView(so.id(), so.sellerId(), so.status().name(), so.shipping().amount(), so.lines().stream()
              .map(l -> new LineView(l.id(), l.offerId(), l.productId(), l.kind(), l.sku(), l.title(), l.variantAttributes(), l.unitPrice().amount(), l.quantity(),
                  l.lineTotal().amount(), l.tax().amount())).toList())).toList());
    }
  }

  /** Customer: own orders only (Layer 2 ownership, ADR-0016). */
  public List<OrderView> myOrders(UserContext user) {
    UUID customer = user.requireActor();
    return transactions.inScope(deployment.scope(), () -> orders.findByCustomer(deployment.scope(), customer, 100).stream().map(OrderView::of).toList());
  }

  /** Customer: one own order; another customer's order is indistinguishable from a missing one (404). */
  public OrderView myOrder(UserContext user, UUID orderId) {
    UUID customer = user.requireActor();
    return transactions.inScope(deployment.scope(), () -> orders.findById(deployment.scope(), orderId).filter(o -> o.customerId().equals(customer))
        .map(OrderView::of).orElseThrow(() -> HiveException.notFound("Order not found")));
  }

  public List<OrderView> storeOrders(UserContext user) {
    operator(user);
    return transactions.inScope(deployment.scope(), () -> orders.findRecent(deployment.scope(), 200).stream().map(OrderView::of).toList());
  }

  public OrderView storeOrder(UserContext user, UUID orderId) {
    operator(user);
    return transactions.inScope(deployment.scope(), () -> orders.findById(deployment.scope(), orderId).map(OrderView::of)
        .orElseThrow(() -> HiveException.notFound("Order not found")));
  }

  /** Seller workspace read: only the seller's part of the order. */
  public Optional<Order> orderOfSellerOrder(TenantScope scope, UUID sellerOrderId) {
    return orders.findBySellerOrder(scope, sellerOrderId);
  }

  private void operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
  }

  void apply(TenantScope scope, UUID orderId, java.util.function.Consumer<Order> change) {
    orders.findById(scope, orderId).ifPresent(order -> {
      change.accept(order);
      save(scope, order);
    });
  }

  void applyToSellerOrder(TenantScope scope, UUID sellerOrderId, java.util.function.Consumer<Order> change) {
    orders.findBySellerOrder(scope, sellerOrderId).ifPresent(order -> {
      change.accept(order);
      save(scope, order);
    });
  }

  private void save(TenantScope scope, Order order) {
    orders.update(order);
    outbox.append(scope, order.pullEvents());
  }

  private String number() {
    var suffix = new StringBuilder();
    for (int i = 0; i < 6; i++) suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    return "HC-" + DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC).format(clock.instant()) + "-" + suffix;
  }

  /** Payment → Ordering: captured payments mark orders paid; failed ones cancel them. */
  @Component
  static class PaymentOutcomeHandler implements EventHandler {
    private final OrderingService ordering;
    private final Clock clock;

    PaymentOutcomeHandler(OrderingService ordering, Clock clock) {
      this.ordering = ordering;
      this.clock = clock;
    }

    public String consumer() { return "ordering.payment-outcome"; }
    public Set<String> eventTypes() { return Set.of("commerce.payment.PaymentCaptured", "commerce.payment.PaymentFailed"); }

    public void handle(EventEnvelope e) {
      var p = e.payload();
      var scope = new TenantScope(e.tenantId(), e.storeId());
      UUID orderId = UUID.fromString((String) p.get("orderId"));
      if (e.type().endsWith("PaymentCaptured")) {
        var amount = new Money(new BigDecimal((String) p.get("amount")), Currency.getInstance((String) p.get("currency")));
        ordering.apply(scope, orderId, o -> o.markPaid(UUID.fromString((String) p.get("paymentId")), amount, clock.instant()));
      } else {
        ordering.apply(scope, orderId, o -> { if (o.status() == Order.Status.PLACED) o.cancel("PAYMENT_FAILED", clock.instant()); });
      }
    }
  }

  /** Inventory → Ordering: an unpaid order whose reservation expired is cancelled. */
  @Component
  static class ReservationExpiredHandler implements EventHandler {
    private final OrderingService ordering;
    private final OrderRepository orders;
    private final Clock clock;

    ReservationExpiredHandler(OrderingService ordering, OrderRepository orders, Clock clock) {
      this.ordering = ordering;
      this.orders = orders;
      this.clock = clock;
    }

    public String consumer() { return "ordering.reservation-expired"; }
    public Set<String> eventTypes() { return Set.of("commerce.inventory.ReservationExpired"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      orders.findByAttempt(scope, UUID.fromString((String) e.payload().get("checkoutAttemptId"))).ifPresent(order ->
          ordering.apply(scope, order.id(), o -> { if (o.status() == Order.Status.PLACED) o.cancel("RESERVATION_EXPIRED", clock.instant()); }));
    }
  }

  /** Fulfillment → Ordering: shipment and delivery of seller orders. */
  @Component
  static class FulfillmentHandler implements EventHandler {
    private final OrderingService ordering;
    private final Clock clock;

    FulfillmentHandler(OrderingService ordering, Clock clock) {
      this.ordering = ordering;
      this.clock = clock;
    }

    public String consumer() { return "ordering.fulfillment"; }
    public Set<String> eventTypes() { return Set.of("commerce.fulfillment.SellerOrderShipped", "commerce.fulfillment.SellerOrderDelivered"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      UUID sellerOrderId = UUID.fromString((String) e.payload().get("sellerOrderId"));
      if (e.type().endsWith("Shipped")) ordering.applyToSellerOrder(scope, sellerOrderId, o -> o.sellerOrderShipped(sellerOrderId, clock.instant()));
      else ordering.applyToSellerOrder(scope, sellerOrderId, o -> o.sellerOrderDelivered(sellerOrderId, clock.instant()));
    }
  }
}
