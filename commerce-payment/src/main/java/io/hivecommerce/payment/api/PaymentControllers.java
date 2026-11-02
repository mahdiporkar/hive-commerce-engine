package io.hivecommerce.payment.api;

import io.hivecommerce.payment.application.PaymentService;
import io.hivecommerce.payment.application.PaymentService.PaymentView;
import io.hivecommerce.payment.application.PaymentService.RefundView;
import io.hivecommerce.payment.domain.PaymentProvider;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import io.hivecommerce.platform.web.ProviderCallback;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

final class PaymentControllers {
  private PaymentControllers() {}

  /** Customer verification after the provider redirect (HYBRID; owner only). */
  @RestController
  @RequestMapping("/v1/shop")
  static class ShopPayments {
    private final PaymentService payments;

    ShopPayments(PaymentService payments) {
      this.payments = payments;
    }

    @PostMapping("/payments/{paymentId}/verify")
    @HiveOperation("shop-payment-verify")
    PaymentView verify(UserContext user, @PathVariable UUID paymentId) { return payments.verify(user, paymentId); }
  }

  /** Operator refunds (AUTHENTICATED, commerce.api.orders:refund). */
  @RestController
  @RequestMapping("/v1/admin")
  static class AdminRefunds {
    private final PaymentService payments;

    AdminRefunds(PaymentService payments) {
      this.payments = payments;
    }

    record RefundBody(String idempotencyKey, BigDecimal amount, List<UUID> orderLineIds) {}

    @PostMapping("/orders/{orderId}/refunds")
    @HiveOperation("admin-order-refund")
    RefundView refund(UserContext user, @PathVariable UUID orderId, @RequestBody RefundBody body) {
      return payments.refundOrder(user, orderId, body.idempotencyKey(), body.amount(), body.orderLineIds());
    }
  }

  /**
   * Webhook ingress for payment providers (ADR-0007, Gap 3). Reached directly through the ingress rule for /hooks/, never
   * through the Hive BFF (no CSRF, no session). Requests carrying any session or identity material are refused.
   */
  @RestController
  @RequestMapping("/hooks")
  static class Webhooks {
    private final PaymentService payments;

    Webhooks(PaymentService payments) {
      this.payments = payments;
    }

    @PostMapping("/payments/{provider}")
    @ProviderCallback
    ResponseEntity<Map<String, String>> receive(@PathVariable String provider, HttpServletRequest request, @RequestBody byte[] body) {
      if (request.getHeader("Authorization") != null || request.getHeader("Cookie") != null
          || Collections.list(request.getHeaderNames()).stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith("x-hive-"))) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "WEBHOOK_SESSION_REFUSED"));
      }
      var headers = new HashMap<String, String>();
      Collections.list(request.getHeaderNames()).forEach(h -> headers.put(h.toLowerCase(Locale.ROOT), request.getHeader(h)));
      try {
        boolean fresh = payments.receiveWebhook(provider, headers, body);
        return ResponseEntity.ok(Map.of("status", fresh ? "processed" : "duplicate"));
      } catch (PaymentProvider.InvalidWebhookException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "WEBHOOK_REJECTED"));
      }
    }

    /** FAKE PSP page actions (only when the fake provider is enabled): the shopper approves or declines a payment. */
    @PostMapping("/fake-psp/sessions/{ref}/{decision}")
    @ProviderCallback
    ResponseEntity<Map<String, String>> fakeDecision(@PathVariable String ref, @PathVariable String decision) {
      if (!(decision.equals("approve") || decision.equals("decline"))) return ResponseEntity.notFound().build();
      try {
        return payments.simulateShopperDecision(ref, decision.equals("approve")).map(url -> ResponseEntity.ok(Map.of("returnUrl", url)))
            .orElseGet(() -> ResponseEntity.notFound().build());
      } catch (IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SESSION_CLOSED"));
      }
    }
  }
}
