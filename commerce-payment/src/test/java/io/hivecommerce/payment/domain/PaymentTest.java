package io.hivecommerce.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

  private static Payment payment() {
    return Payment.initiate(UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.of("1000000", "IRR"), "fake", NOW);
  }

  @Test
  void captureIsIdempotentAndChecksTheAmount() {
    var p = payment();
    assertThatThrownBy(() -> p.capture("tx1", Money.of("999999", "IRR"), NOW)).isInstanceOf(DomainRuleException.class);
    assertThat(p.capture("tx1", Money.of("1000000", "IRR"), NOW)).isTrue();
    assertThat(p.capture("tx1", Money.of("1000000", "IRR"), NOW)).isFalse();
    assertThatThrownBy(() -> p.capture("tx2", Money.of("1000000", "IRR"), NOW)).isInstanceOf(DomainRuleException.class);
    assertThat(p.fail("late decline", NOW)).isFalse();
    assertThat(p.status()).isEqualTo(Payment.Status.CAPTURED);
  }

  @Test
  void refundsNeverExceedTheCaptureAndAreIdempotent() {
    var p = payment();
    assertThatThrownBy(() -> p.requestRefund(UUID.randomUUID(), "r0", Money.of("1", "IRR"), List.of(), NOW)).isInstanceOf(DomainRuleException.class);
    p.capture("tx1", Money.of("1000000", "IRR"), NOW);
    var first = p.requestRefund(UUID.randomUUID(), "r1", Money.of("600000", "IRR"), List.of(), NOW);
    assertThat(p.requestRefund(UUID.randomUUID(), "r1", Money.of("600000", "IRR"), List.of(), NOW).id()).isEqualTo(first.id());
    assertThatThrownBy(() -> p.requestRefund(UUID.randomUUID(), "r2", Money.of("400001", "IRR"), List.of(), NOW))
        .isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_CAPTURE"));
    p.requestRefund(UUID.randomUUID(), "r3", Money.of("400000", "IRR"), List.of(), NOW);
    assertThat(p.refundable()).isEqualTo(Money.of("0", "IRR"));
  }

  @Test
  void declineFailsAnInitiatedPaymentOnce() {
    var p = payment();
    assertThat(p.fail("DECLINED", NOW)).isTrue();
    assertThat(p.fail("DECLINED", NOW)).isFalse();
    assertThat(p.capture("tx1", Money.of("1000000", "IRR"), NOW)).isTrue();
  }
}
