package io.hivecommerce.fulfillment.domain;

import io.hivecommerce.fulfillment.domain.event.FulfillmentEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Physical delivery of one seller order (aggregate root): PENDING → SHIPPED → DELIVERED. */
public final class Fulfillment {
  public enum Status { PENDING, SHIPPED, DELIVERED }

  private final UUID id;
  private final TenantScope scope;
  private final UUID orderId;
  private final UUID sellerOrderId;
  private final UUID sellerId;
  private Status status;
  private String carrier;
  private String trackingCode;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Fulfillment(UUID id, TenantScope scope, UUID orderId, UUID sellerOrderId, UUID sellerId, Status status, String carrier, String trackingCode, long revision) {
    this.id = id;
    this.scope = scope;
    this.orderId = orderId;
    this.sellerOrderId = sellerOrderId;
    this.sellerId = sellerId;
    this.status = status;
    this.carrier = carrier;
    this.trackingCode = trackingCode;
    this.revision = revision;
  }

  public static Fulfillment open(UUID id, TenantScope scope, UUID orderId, UUID sellerOrderId, UUID sellerId) {
    return new Fulfillment(id, scope, orderId, sellerOrderId, sellerId, Status.PENDING, null, null, 0);
  }

  public static Fulfillment restore(UUID id, TenantScope scope, UUID orderId, UUID sellerOrderId, UUID sellerId, Status status, String carrier, String trackingCode,
      long revision) {
    return new Fulfillment(id, scope, orderId, sellerOrderId, sellerId, status, carrier, trackingCode, revision);
  }

  public void ship(String carrier, String trackingCode, Instant now) {
    if (status != Status.PENDING) throw new DomainRuleException("FULFILLMENT_STATUS_INVALID", "Only pending fulfillments can be shipped");
    if (carrier == null || carrier.isBlank() || carrier.length() > 80) throw DomainValidationException.of("carrier", "1 to 80 characters");
    if (trackingCode != null && trackingCode.length() > 120) throw DomainValidationException.of("trackingCode", "at most 120 characters");
    this.status = Status.SHIPPED;
    this.carrier = carrier.strip();
    this.trackingCode = trackingCode;
    events.add(new FulfillmentEvent("SellerOrderShipped", id.toString(), Map.of("orderId", orderId.toString(), "sellerOrderId", sellerOrderId.toString(),
        "sellerId", sellerId.toString(), "carrier", this.carrier), now));
  }

  public void confirmDelivery(Instant now) {
    if (status != Status.SHIPPED) throw new DomainRuleException("FULFILLMENT_STATUS_INVALID", "Only shipped fulfillments can be delivered");
    status = Status.DELIVERED;
    events.add(new FulfillmentEvent("SellerOrderDelivered", id.toString(), Map.of("orderId", orderId.toString(), "sellerOrderId", sellerOrderId.toString(),
        "sellerId", sellerId.toString()), now));
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID orderId() { return orderId; }
  public UUID sellerOrderId() { return sellerOrderId; }
  public UUID sellerId() { return sellerId; }
  public Status status() { return status; }
  public String carrier() { return carrier; }
  public String trackingCode() { return trackingCode; }
  public long revision() { return revision; }
}
