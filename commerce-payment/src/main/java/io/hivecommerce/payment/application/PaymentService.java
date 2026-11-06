package io.hivecommerce.payment.application;

import io.hivecommerce.payment.application.published.PaymentApi;
import io.hivecommerce.payment.domain.Payment;
import io.hivecommerce.payment.domain.PaymentProvider;
import io.hivecommerce.payment.domain.PaymentRepository;
import io.hivecommerce.payment.infrastructure.PaymentOutbox;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Payment use cases (ADR-0007). Two independent confirmation paths (customer-triggered verification after the return
 * URL, and signed provider webhooks through the inbox) converge on {@link #applyVerification}, which is idempotent.
 */
@Service
public class PaymentService implements PaymentApi {
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final PaymentRepository payments;
  private final PaymentOutbox outbox;
  private final Map<String, PaymentProvider> providers;
  private final String activeProvider;
  private final String returnUrl;
  private final Clock clock;

  public PaymentService(DeploymentScope deployment, TenantTransactions transactions, PaymentRepository payments, PaymentOutbox outbox,
      List<PaymentProvider> providers, @Value("${commerce.payments.provider:}") String activeProvider,
      @Value("${commerce.payments.return-url:}") String returnUrl, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.payments = payments;
    this.outbox = outbox;
    this.providers = providers.stream().collect(java.util.stream.Collectors.toMap(PaymentProvider::key, p -> p));
    this.activeProvider = activeProvider;
    this.returnUrl = returnUrl;
    this.clock = clock;
  }

  private PaymentProvider provider(String key) {
    var provider = providers.get(key);
    if (provider == null) throw new HiveException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE", "No payment provider is configured");
    return provider;
  }

  @Override
  public PaymentStart initiate(TenantScope scope, UUID orderId, UUID attemptId, UUID customerId, Money amount) {
    var provider = provider(activeProvider);
    var payment = transactions.inScope(scope, () -> payments.findByAttempt(scope, attemptId).orElseGet(() -> {
      var created = Payment.initiate(Ids.newId(), scope, orderId, attemptId, customerId, amount, provider.key(), clock.instant());
      payments.insert(created);
      outbox.append(scope, created.pullEvents());
      return created;
    }));
    if (payment.status() != Payment.Status.INITIATED) return new PaymentStart(payment.id(), null);
    // Outside the database transaction: a provider call must never hold row locks.
    var initiation = provider.initiate(payment.id(), amount, returnUrl + (returnUrl.contains("?") ? "&" : "?") + "payment=" + payment.id());
    transactions.inScope(scope, () -> {
      var current = payments.findById(scope, payment.id()).orElseThrow();
      if (current.providerRef() == null) {
        current.attachProviderReference(initiation.providerRef());
        payments.update(current);
      }
      return null;
    });
    return new PaymentStart(payment.id(), initiation.redirectUrl());
  }

  public record PaymentView(UUID paymentId, UUID orderId, String status, BigDecimal amount, String currency) {
    static PaymentView of(Payment p) { return new PaymentView(p.id(), p.orderId(), p.status().name(), p.amount().amount(), p.amount().currency().getCurrencyCode()); }
  }

  /** Customer-triggered verification after returning from the provider (the redirect itself proves nothing). */
  public PaymentView verify(UserContext user, UUID paymentId) {
    UUID customer = user.requireActor();
    TenantScope scope = deployment.scope();
    var payment = transactions.inScope(scope, () -> payments.findById(scope, paymentId).filter(p -> p.customerId().equals(customer))
        .orElseThrow(() -> HiveException.notFound("Payment not found")));
    if (payment.status() == Payment.Status.INITIATED && payment.providerRef() != null) {
      applyVerification(scope, payment.id(), provider(payment.provider()).verify(payment.providerRef()));
    }
    return transactions.inScope(scope, () -> PaymentView.of(payments.findById(scope, paymentId).orElseThrow()));
  }

  /** Provider callback: authenticated by the adapter, deduplicated by the inbox, then applied idempotently. */
  public boolean receiveWebhook(String providerKey, Map<String, String> headers, byte[] body) {
    var provider = provider(providerKey);
    var event = provider.parseWebhook(headers, body);
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!payments.recordWebhook(scope, providerKey, event.eventId(), event.providerRef(), new String(body, java.nio.charset.StandardCharsets.UTF_8))) return false;
      var payment = payments.findByProviderRef(scope, providerKey, event.providerRef())
          .orElseThrow(() -> new DomainRuleException("PAYMENT_UNKNOWN", "Unknown provider reference"));
      apply(scope, payment, event.verification());
      return true;
    });
  }

  void applyVerification(TenantScope scope, UUID paymentId, PaymentProvider.Verification verification) {
    transactions.inScope(scope, () -> {
      apply(scope, payments.findById(scope, paymentId).orElseThrow(), verification);
      return null;
    });
  }

  private void apply(TenantScope scope, Payment payment, PaymentProvider.Verification v) {
    boolean changed = switch (v.outcome()) {
      case PAID -> payment.capture(v.providerTxId(), v.amount(), clock.instant());
      case DECLINED -> payment.fail("DECLINED", clock.instant());
      case PENDING -> false;
    };
    if (changed) {
      payments.update(payment);
      outbox.append(scope, payment.pullEvents());
    }
  }

  /** Safety net: re-verifies payments still INITIATED after a grace period (missing webhooks, PSPs needing verify). */
  @Scheduled(fixedDelayString = "${commerce.payments.sweep-interval-ms:60000}", initialDelayString = "${commerce.payments.sweep-interval-ms:60000}")
  public void sweep() {
    TenantScope scope = deployment.scope();
    var stale = transactions.inScope(scope, () -> payments.findInitiatedBefore(scope, clock.instant().minus(Duration.ofMinutes(2))));
    for (var p : stale) {
      if (p.providerRef() == null || !providers.containsKey(p.provider())) continue;
      try {
        applyVerification(scope, p.id(), providers.get(p.provider()).verify(p.providerRef()));
      } catch (RuntimeException e) {
        log.warn("Verification sweep failed for payment {}", p.id(), e);
      }
    }
  }

  /** Development and tests only: plays the shopper's decision at a simulated provider. Empty when no simulator is active. */
  public java.util.Optional<String> simulateShopperDecision(String providerRef, boolean approve) {
    return providers.values().stream().filter(p -> p.testOnly() && p instanceof PaymentProvider.Simulator).findFirst()
        .map(p -> ((PaymentProvider.Simulator) p).decide(providerRef, approve));
  }

  // ---- refunds ----

  public record RefundView(UUID refundId, UUID paymentId, BigDecimal amount, String status) {}

  /** Operator refund (Layer 1: commerce.api.orders:refund). Idempotent per key; never exceeds the captured amount. */
  /** Operator: the latest payments, without any provider credential or raw provider payload. */
  public List<PaymentView> recentPayments(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> payments.findRecent(scope, 200).stream().map(PaymentView::of).toList());
  }

  public RefundView refundOrder(UserContext user, UUID orderId, String idempotencyKey, BigDecimal amount, List<UUID> orderLineIds) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) throw io.hivecommerce.shared.DomainValidationException.of("idempotencyKey", "1 to 100 characters");
    TenantScope scope = deployment.scope();
    var requested = transactions.inScope(scope, () -> {
      var payment = payments.findByOrder(scope, orderId).orElseThrow(() -> HiveException.notFound("No payment for this order"));
      var refund = payment.requestRefund(Ids.newId(), idempotencyKey, new Money(amount, payment.amount().currency()),
          orderLineIds == null ? List.of() : orderLineIds, clock.instant());
      payments.update(payment);
      return new Object[] {payment, refund};
    });
    var payment = (Payment) requested[0];
    var refund = (Payment.Refund) requested[1];
    if (refund.status() == Payment.RefundStatus.REQUESTED) {
      String providerRefundId = provider(payment.provider()).refund(payment.providerTxId(), refund.amount(), refund.idempotencyKey());
      transactions.inScope(scope, () -> {
        var current = payments.findById(scope, payment.id()).orElseThrow();
        current.completeRefund(refund.id(), providerRefundId, clock.instant());
        payments.update(current);
        outbox.append(scope, current.pullEvents());
        return null;
      });
    }
    return new RefundView(refund.id(), payment.id(), refund.amount().amount(), Payment.RefundStatus.COMPLETED.name());
  }

  /** Ordering → Payment: money captured for an order that can no longer be fulfilled is refunded in full automatically. */
  @Component
  static class UnfulfillableRefundHandler implements EventHandler {
    private final PaymentService service;
    private final PaymentRepository payments;
    private final Clock clock;

    UnfulfillableRefundHandler(PaymentService service, PaymentRepository payments, Clock clock) {
      this.service = service;
      this.payments = payments;
      this.clock = clock;
    }

    public String consumer() { return "payment.order-unfulfillable"; }
    public Set<String> eventTypes() { return Set.of("commerce.ordering.OrderPaidUnfulfillable"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      var payment = payments.findById(scope, UUID.fromString((String) e.payload().get("paymentId"))).orElseThrow();
      var refund = payment.requestRefund(Ids.newId(), "auto-unfulfillable-" + payment.id(), payment.refundable(), List.of(), clock.instant());
      String providerRefundId = service.provider(payment.provider()).refund(payment.providerTxId(), refund.amount(), refund.idempotencyKey());
      payment.completeRefund(refund.id(), providerRefundId, clock.instant());
      payments.update(payment);
      service.outbox.append(scope, payment.pullEvents());
    }
  }

}
