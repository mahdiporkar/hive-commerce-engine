package io.hivecommerce.checkout.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.UUID;

/**
 * One idempotent checkout attempt and the state of the CheckoutProcess saga (docs/domain/aggregates.md):
 * STARTED → RESERVED → ORDERED → PAYMENT_INITIATED → COMPLETED, or FAILED (nothing to undo) / COMPENSATED.
 */
public final class CheckoutAttempt {
  public enum State { STARTED, RESERVED, ORDERED, PAYMENT_INITIATED, COMPLETED, FAILED, COMPENSATED }

  private final UUID id;
  private final TenantScope scope;
  private final UUID customerId;
  private final String idempotencyKey;
  private final UUID cartId;
  private State state;
  private UUID orderId;
  private String orderNumber;
  private Money total;
  private UUID paymentId;
  private String redirectUrl;
  private String failureCode;
  private final long revision;

  private CheckoutAttempt(UUID id, TenantScope scope, UUID customerId, String idempotencyKey, UUID cartId, State state, UUID orderId, String orderNumber,
      Money total, UUID paymentId, String redirectUrl, String failureCode, long revision) {
    this.id = id;
    this.scope = scope;
    this.customerId = customerId;
    this.idempotencyKey = idempotencyKey;
    this.cartId = cartId;
    this.state = state;
    this.orderId = orderId;
    this.orderNumber = orderNumber;
    this.total = total;
    this.paymentId = paymentId;
    this.redirectUrl = redirectUrl;
    this.failureCode = failureCode;
    this.revision = revision;
  }

  public static CheckoutAttempt start(UUID id, TenantScope scope, UUID customerId, String idempotencyKey, UUID cartId) {
    return new CheckoutAttempt(id, scope, customerId, idempotencyKey, cartId, State.STARTED, null, null, null, null, null, null, 0);
  }

  public static CheckoutAttempt restore(UUID id, TenantScope scope, UUID customerId, String key, UUID cartId, State state, UUID orderId, String orderNumber,
      Money total, UUID paymentId, String redirectUrl, String failureCode, long revision) {
    return new CheckoutAttempt(id, scope, customerId, key, cartId, state, orderId, orderNumber, total, paymentId, redirectUrl, failureCode, revision);
  }

  public void reserved() { move(State.STARTED, State.RESERVED); }

  public void ordered(UUID orderId, String number, Money total) {
    move(State.RESERVED, State.ORDERED);
    this.orderId = orderId;
    this.orderNumber = number;
    this.total = total;
  }

  public void paymentInitiated(UUID paymentId, String redirectUrl) {
    move(State.ORDERED, State.PAYMENT_INITIATED);
    this.paymentId = paymentId;
    this.redirectUrl = redirectUrl;
  }

  public void completed() {
    if (state == State.COMPLETED) return;
    move(State.PAYMENT_INITIATED, State.COMPLETED);
  }

  public void failed(String code) {
    if (state != State.STARTED) throw new DomainRuleException("CHECKOUT_STATE_INVALID", "Only a started checkout can fail without compensation");
    state = State.FAILED;
    failureCode = code;
  }

  public void compensated(String code) {
    if (state == State.COMPENSATED || state == State.COMPLETED || state == State.FAILED) return;
    state = State.COMPENSATED;
    failureCode = code;
  }

  public boolean settled() { return state == State.PAYMENT_INITIATED || state == State.COMPLETED || state == State.FAILED || state == State.COMPENSATED; }

  private void move(State from, State to) {
    if (state != from) throw new DomainRuleException("CHECKOUT_STATE_INVALID", "Cannot move a checkout from " + state + " to " + to);
    state = to;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID customerId() { return customerId; }
  public String idempotencyKey() { return idempotencyKey; }
  public UUID cartId() { return cartId; }
  public State state() { return state; }
  public UUID orderId() { return orderId; }
  public String orderNumber() { return orderNumber; }
  public Money total() { return total; }
  public UUID paymentId() { return paymentId; }
  public String redirectUrl() { return redirectUrl; }
  public String failureCode() { return failureCode; }
  public long revision() { return revision; }
}
