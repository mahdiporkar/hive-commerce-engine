package io.hivecommerce.payment.domain;

import io.hivecommerce.shared.Money;
import java.util.Map;
import java.util.UUID;

/**
 * Port to a payment service provider (ADR-0007). Adapters live in infrastructure (fake for development and tests) or in
 * client repositories (real PSPs); the core never hardcodes a gateway.
 */
public interface PaymentProvider {
  record Initiation(String providerRef, String redirectUrl) {}

  enum Outcome { PAID, DECLINED, PENDING }

  record Verification(Outcome outcome, String providerTxId, Money amount) {}

  record WebhookEvent(String eventId, String providerRef, Verification verification) {}

  class InvalidWebhookException extends RuntimeException {
    public InvalidWebhookException(String message) { super(message); }
  }

  /** Implemented only by simulated providers: plays the shopper's decision on the provider page (development and tests). */
  interface Simulator {
    /** @return the provider's return URL for the session */
    String decide(String providerRef, boolean approve);
  }

  String key();

  /** True for providers that must never run in production (the ProductionGuard refuses them). */
  boolean testOnly();

  Initiation initiate(UUID paymentId, Money amount, String returnUrl);

  /** Asks the provider for the authoritative result. */
  Verification verify(String providerRef);

  /** Parses and authenticates a provider callback (signature check inside the adapter). */
  WebhookEvent parseWebhook(Map<String, String> headers, byte[] body);

  /** Executes a refund at the provider; returns the provider's refund id. */
  String refund(String providerTxId, Money amount, String idempotencyKey);
}
