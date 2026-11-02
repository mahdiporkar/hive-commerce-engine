package io.hivecommerce.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  private final UUID sellerA = UUID.randomUUID(), sellerB = UUID.randomUUID();

  private Order.LineDraft line(UUID seller, String kind, String total) {
    return new Order.LineDraft(seller, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), kind, "SKU-" + total, Map.of("en", "Item"), Map.of(),
        Money.of(total, "IRR"), 1, Money.of(total, "IRR"), Money.of("0", "IRR"));
  }

  private Order place(String expectedTotal) {
    return Order.place(UUID.randomUUID(), SCOPE, "HC-1", UUID.randomUUID(), UUID.randomUUID(),
        List.of(line(sellerA, "PHYSICAL", "1000"), line(sellerB, "DIGITAL", "500"), line(sellerA, "PHYSICAL", "250")),
        Map.of(sellerA, Money.of("100", "IRR")), Money.of(expectedTotal, "IRR"), Ids::newId, NOW);
  }

  @Test
  void splitsBySellerAndChecksTheTotal() {
    var order = place("1850");
    assertThat(order.sellerOrders()).hasSize(2);
    assertThat(order.sellerOrders().getFirst().lines()).hasSize(2);
    assertThatThrownBy(() -> place("1849")).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("ORDER_TOTAL_MISMATCH"));
  }

  @Test
  void paymentIsIdempotentAndMustMatchTheTotal() {
    var order = place("1850");
    order.pullEvents();
    assertThatThrownBy(() -> order.markPaid(UUID.randomUUID(), Money.of("10", "IRR"), NOW)).isInstanceOf(DomainRuleException.class);
    order.markPaid(UUID.randomUUID(), Money.of("1850", "IRR"), NOW);
    order.markPaid(UUID.randomUUID(), Money.of("1850", "IRR"), NOW);
    assertThat(order.status()).isEqualTo(Order.Status.PAID);
    assertThat(order.pullEvents()).singleElement().satisfies(e -> {
      assertThat(e.type()).isEqualTo("commerce.ordering.OrderPaid");
      assertThat((List<?>) e.payload().get("lines")).hasSize(3);
    });
    assertThatThrownBy(() -> order.cancel("late", NOW)).isInstanceOf(DomainRuleException.class);
  }

  @Test
  void captureAfterCancellationNeedsARefund() {
    var order = place("1850");
    order.cancel("RESERVATION_EXPIRED", NOW);
    order.markPaid(UUID.randomUUID(), Money.of("1850", "IRR"), NOW);
    assertThat(order.status()).isEqualTo(Order.Status.PAID_UNFULFILLABLE);
    assertThat(order.pullEvents()).extracting(e -> e.type()).containsExactly("commerce.ordering.OrderPlaced", "commerce.ordering.OrderCancelled",
        "commerce.ordering.OrderPaidUnfulfillable");
  }

  @Test
  void completesWhenEverySellerOrderIsDelivered() {
    var order = place("1850");
    order.markPaid(UUID.randomUUID(), Money.of("1850", "IRR"), NOW);
    var physical = order.sellerOrders().stream().filter(Order.SellerOrder::hasPhysical).findFirst().orElseThrow();
    var digital = order.sellerOrders().stream().filter(so -> !so.hasPhysical()).findFirst().orElseThrow();
    assertThatThrownBy(() -> order.sellerOrderDelivered(UUID.randomUUID(), NOW)).isInstanceOf(DomainRuleException.class);
    order.sellerOrderShipped(physical.id(), NOW);
    order.sellerOrderDelivered(physical.id(), NOW);
    assertThat(order.status()).isEqualTo(Order.Status.PAID);
    order.sellerOrderDelivered(digital.id(), NOW);
    assertThat(order.status()).isEqualTo(Order.Status.COMPLETED);
  }
}
