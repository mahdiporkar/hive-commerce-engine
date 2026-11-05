package io.hivecommerce.fulfillment.application;

import io.hivecommerce.fulfillment.domain.Fulfillment;
import io.hivecommerce.fulfillment.infrastructure.FulfillmentOutbox;
import io.hivecommerce.fulfillment.infrastructure.JdbcFulfillmentRepository;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** Fulfillment use cases for operators (internal seller) and sellers (own seller orders, Phase 4). */
@Service
public class FulfillmentService {
  public record FulfillmentView(UUID fulfillmentId, UUID orderId, UUID sellerOrderId, UUID sellerId, String status, String carrier, String trackingCode, long revision) {
    static FulfillmentView of(Fulfillment f) {
      return new FulfillmentView(f.id(), f.orderId(), f.sellerOrderId(), f.sellerId(), f.status().name(), f.carrier(), f.trackingCode(), f.revision());
    }
  }

  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final JdbcFulfillmentRepository fulfillments;
  private final FulfillmentOutbox outbox;
  private final Clock clock;
  private final io.hivecommerce.seller.application.published.SellerDirectory sellers;

  public FulfillmentService(DeploymentScope deployment, TenantTransactions transactions, JdbcFulfillmentRepository fulfillments, FulfillmentOutbox outbox, Clock clock,
      io.hivecommerce.seller.application.published.SellerDirectory sellers) {
    this.sellers = sellers;
    this.deployment = deployment;
    this.transactions = transactions;
    this.fulfillments = fulfillments;
    this.outbox = outbox;
    this.clock = clock;
  }

  public List<FulfillmentView> ofOrder(UserContext user, UUID orderId) {
    operator(user);
    return transactions.inScope(deployment.scope(), () -> fulfillments.findByOrder(deployment.scope(), orderId).stream().map(FulfillmentView::of).toList());
  }

  public FulfillmentView ship(UserContext user, UUID fulfillmentId, String carrier, String trackingCode) {
    operator(user);
    return change(fulfillmentId, (f, now) -> { f.ship(carrier, trackingCode, now); return true; }, null, null);
  }

  public FulfillmentView deliver(UserContext user, UUID fulfillmentId) {
    operator(user);
    return change(fulfillmentId, (f, now) -> { f.confirmDelivery(now); return true; }, null, null);
  }

  /** Seller workspace: another seller's fulfillment is indistinguishable from a missing one. */
  public FulfillmentView shipAsSeller(UserContext user, UUID fulfillmentId, String carrier, String trackingCode) {
    UUID actor = user.requireActor();
    return change(fulfillmentId, (f, now) -> { f.ship(carrier, trackingCode, now); return true; }, (seller, capability) -> sellers.allows(deployment.scope(), actor, seller, capability), "orders.fulfill");
  }

  public List<FulfillmentView> ofSeller(UserContext user, UUID sellerId) {
    UUID actor = user.requireActor();
    return transactions.inScope(deployment.scope(), () -> {
      if (!sellers.allows(deployment.scope(), actor, sellerId, "orders.view")) throw HiveException.notFound("Seller not found");
      return fulfillments.findBySeller(deployment.scope(), sellerId).stream().map(FulfillmentView::of).toList();
    });
  }

  private FulfillmentView change(UUID id, java.util.function.BiFunction<Fulfillment, java.time.Instant, Boolean> action, BiPredicate<UUID, String> allowed,
      String capability) {
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var f = fulfillments.findById(scope, id).filter(x -> allowed == null || allowed.test(x.sellerId(), capability))
          .orElseThrow(() -> HiveException.notFound("Fulfillment not found"));
      action.apply(f, clock.instant());
      fulfillments.update(f);
      outbox.append(scope, f.pullEvents());
      return FulfillmentView.of(f);
    });
  }

  private void operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
  }

  /** Ordering → Fulfillment: each paid seller order with physical lines needs a shipment. */
  @Component
  static class OrderPaidHandler implements EventHandler {
    private final JdbcFulfillmentRepository fulfillments;

    OrderPaidHandler(JdbcFulfillmentRepository fulfillments) {
      this.fulfillments = fulfillments;
    }

    public String consumer() { return "fulfillment.order-paid"; }
    public Set<String> eventTypes() { return Set.of("commerce.ordering.OrderPaid"); }

    @SuppressWarnings("unchecked")
    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      UUID orderId = UUID.fromString((String) e.payload().get("orderId"));
      for (var so : (List<Map<String, Object>>) e.payload().get("sellerOrders")) {
        if (!Boolean.TRUE.equals(so.get("hasPhysical"))) continue;
        fulfillments.insertIfAbsent(Fulfillment.open(Ids.newId(), scope, orderId, UUID.fromString((String) so.get("sellerOrderId")),
            UUID.fromString((String) so.get("sellerId"))));
      }
    }
  }
}
