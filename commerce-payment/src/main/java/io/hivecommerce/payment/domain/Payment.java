package io.hivecommerce.payment.domain;

import io.hivecommerce.payment.domain.event.PaymentEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money collected for one order through one provider (aggregate root). Invariants: amount and currency equal the order
 * total; capture is idempotent per provider transaction; only a provider-verified result changes state (a browser
 * redirect never does, ADR-0007); refunds never exceed the captured amount (ADR-0015 change 6: refunds live here).
 */
public final class Payment {
  public enum Status { INITIATED, CAPTURED, FAILED }

  public enum RefundStatus { REQUESTED, COMPLETED, FAILED }

  public record Refund(UUID id, String idempotencyKey, Money amount, List<UUID> orderLineIds, RefundStatus status, String providerRefundId) {
    public Refund {
      orderLineIds = List.copyOf(orderLineIds);
    }
  }

  private final UUID id;
  private final TenantScope scope;
  private final UUID orderId;
  private final UUID checkoutAttemptId;
  private final UUID customerId;
  private final Money amount;
  private final String provider;
  private String providerRef;
  private Status status;
  private String providerTxId;
  private final List<Refund> refunds;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Payment(UUID id, TenantScope scope, UUID orderId, UUID checkoutAttemptId, UUID customerId, Money amount, String provider, String providerRef,
      Status status, String providerTxId, List<Refund> refunds, long revision) {
    this.id = id;
    this.scope = scope;
    this.orderId = orderId;
    this.checkoutAttemptId = checkoutAttemptId;
    this.customerId = customerId;
    this.amount = amount;
    this.provider = provider;
    this.providerRef = providerRef;
    this.status = status;
    this.providerTxId = providerTxId;
    this.refunds = new ArrayList<>(refunds);
    this.revision = revision;
  }

  public static Payment initiate(UUID id, TenantScope scope, UUID orderId, UUID checkoutAttemptId, UUID customerId, Money amount, String provider, Instant now) {
    if (!amount.isPositive()) throw new DomainRuleException("PAYMENT_AMOUNT_INVALID", "A payment needs a positive amount");
    var payment = new Payment(id, scope, orderId, checkoutAttemptId, customerId, amount, provider, null, Status.INITIATED, null, List.of(), 0);
    payment.events.add(new PaymentEvent("PaymentInitiated", id.toString(), payload(payment), now));
    return payment;
  }

  public static Payment restore(UUID id, TenantScope scope, UUID orderId, UUID checkoutAttemptId, UUID customerId, Money amount, String provider,
      String providerRef, Status status, String providerTxId, List<Refund> refunds, long revision) {
    return new Payment(id, scope, orderId, checkoutAttemptId, customerId, amount, provider, providerRef, status, providerTxId, refunds, revision);
  }

  public void attachProviderReference(String ref) {
    if (providerRef != null && !providerRef.equals(ref)) throw new DomainRuleException("PROVIDER_REF_FIXED", "The provider reference is already set");
    providerRef = ref;
  }

  /** Applies a provider-verified capture. Returns false when nothing changed (replay or duplicate confirmation). */
  public boolean capture(String verifiedTxId, Money capturedAmount, Instant now) {
    if (status == Status.CAPTURED) {
      if (!providerTxId.equals(verifiedTxId)) throw new DomainRuleException("PAYMENT_ALREADY_CAPTURED", "Captured with another provider transaction");
      return false;
    }
    if (!capturedAmount.equals(amount)) throw new DomainRuleException("PAYMENT_AMOUNT_MISMATCH", "Provider amount differs from the order total");
    // A capture is accepted even after a failure was recorded: the money is real and the order must be refunded or fulfilled.
    status = Status.CAPTURED;
    providerTxId = verifiedTxId;
    events.add(new PaymentEvent("PaymentCaptured", id.toString(), payload(this), now));
    return true;
  }

  public boolean fail(String reason, Instant now) {
    if (status != Status.INITIATED) return false;
    status = Status.FAILED;
    var payload = payload(this);
    payload.put("reason", reason);
    events.add(new PaymentEvent("PaymentFailed", id.toString(), payload, now));
    return true;
  }

  public Money refundable() {
    if (status != Status.CAPTURED) return new Money(BigDecimal.ZERO, amount.currency());
    Money refunded = refunds.stream().filter(r -> r.status() != RefundStatus.FAILED).map(Refund::amount)
        .reduce(new Money(BigDecimal.ZERO, amount.currency()), Money::plus);
    return amount.minus(refunded);
  }

  /** Requests a refund; idempotent per key. Σ refunds ≤ captured is enforced here, in one aggregate. */
  public Refund requestRefund(UUID refundId, String idempotencyKey, Money refundAmount, List<UUID> orderLineIds, Instant now) {
    var existing = refunds.stream().filter(r -> r.idempotencyKey().equals(idempotencyKey)).findFirst();
    if (existing.isPresent()) return existing.get();
    if (status != Status.CAPTURED) throw new DomainRuleException("PAYMENT_NOT_CAPTURED", "Only captured payments can be refunded");
    if (!refundAmount.isPositive()) throw new DomainRuleException("REFUND_AMOUNT_INVALID", "A refund needs a positive amount");
    if (refundAmount.amount().compareTo(refundable().amount()) > 0) throw new DomainRuleException("REFUND_EXCEEDS_CAPTURE", "Refunds cannot exceed the captured amount");
    var refund = new Refund(refundId, idempotencyKey, refundAmount, orderLineIds, RefundStatus.REQUESTED, null);
    refunds.add(refund);
    return refund;
  }

  public void completeRefund(UUID refundId, String providerRefundId, Instant now) {
    for (int i = 0; i < refunds.size(); i++) {
      var r = refunds.get(i);
      if (!r.id().equals(refundId) || r.status() != RefundStatus.REQUESTED) continue;
      refunds.set(i, new Refund(r.id(), r.idempotencyKey(), r.amount(), r.orderLineIds(), RefundStatus.COMPLETED, providerRefundId));
      var payload = payload(this);
      payload.put("refundId", r.id().toString());
      payload.put("refundAmount", r.amount().amount().toPlainString());
      payload.put("orderLineIds", r.orderLineIds().stream().map(UUID::toString).toList());
      events.add(new PaymentEvent("RefundCompleted", id.toString(), payload, now));
      return;
    }
  }

  private static Map<String, Object> payload(Payment p) {
    var m = new HashMap<String, Object>();
    m.put("paymentId", p.id.toString());
    m.put("orderId", p.orderId.toString());
    m.put("checkoutAttemptId", p.checkoutAttemptId.toString());
    m.put("customerId", p.customerId.toString());
    m.put("amount", p.amount.amount().toPlainString());
    m.put("currency", p.amount.currency().getCurrencyCode());
    m.put("provider", p.provider);
    if (p.providerTxId != null) m.put("providerTxId", p.providerTxId);
    return m;
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID orderId() { return orderId; }
  public UUID checkoutAttemptId() { return checkoutAttemptId; }
  public UUID customerId() { return customerId; }
  public Money amount() { return amount; }
  public String provider() { return provider; }
  public String providerRef() { return providerRef; }
  public Status status() { return status; }
  public String providerTxId() { return providerTxId; }
  public List<Refund> refunds() { return List.copyOf(refunds); }
  public long revision() { return revision; }
}
