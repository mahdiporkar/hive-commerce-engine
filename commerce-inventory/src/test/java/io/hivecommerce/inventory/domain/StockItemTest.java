package io.hivecommerce.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockItemTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

  private static StockItem item(int quantity, boolean unique, boolean unlimited) {
    return StockItem.open(UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), quantity, unique, unlimited, NOW);
  }

  @Test
  void uniqueItemSellsAtMostOnce() {
    var gem = item(1, true, false);
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    gem.reserve(UUID.randomUUID(), first, 1, NOW.plusSeconds(900), NOW);
    assertThatThrownBy(() -> gem.reserve(UUID.randomUUID(), second, 1, NOW.plusSeconds(900), NOW))
        .isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("OUT_OF_STOCK"));
    assertThat(gem.consume(first, NOW)).isTrue();
    assertThat(gem.onHand()).isZero();
    assertThat(gem.available()).isZero();
    assertThatThrownBy(() -> item(2, true, false)).isInstanceOf(DomainValidationException.class);
  }

  @Test
  void reservationsCloseExactlyOnce() {
    var bowl = item(3, false, false);
    UUID attempt = UUID.randomUUID();
    bowl.reserve(UUID.randomUUID(), attempt, 2, NOW.plusSeconds(60), NOW);
    bowl.reserve(UUID.randomUUID(), attempt, 2, NOW.plusSeconds(60), NOW); // idempotent per attempt
    assertThat(bowl.available()).isEqualTo(1);
    assertThat(bowl.release(attempt, NOW)).isTrue();
    assertThat(bowl.release(attempt, NOW)).isFalse();
    assertThat(bowl.consume(attempt, NOW)).isFalse();
    assertThat(bowl.available()).isEqualTo(3);
  }

  @Test
  void expiredReservationsReturnStock() {
    var bowl = item(1, false, false);
    UUID attempt = UUID.randomUUID();
    bowl.reserve(UUID.randomUUID(), attempt, 1, NOW.plusSeconds(60), NOW);
    assertThat(bowl.expire(NOW.plusSeconds(30))).isEmpty();
    assertThat(bowl.expire(NOW.plusSeconds(61))).containsExactly(attempt);
    assertThat(bowl.available()).isEqualTo(1);
  }

  @Test
  void unlimitedItemsNeverRunOutAndCannotBeAdjusted() {
    var pdf = item(0, false, true);
    for (int i = 0; i < 5; i++) pdf.reserve(UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusSeconds(60), NOW);
    assertThat(pdf.available()).isEqualTo(Integer.MAX_VALUE);
    assertThatThrownBy(() -> pdf.adjustOnHand(10, NOW)).isInstanceOf(DomainRuleException.class);
  }

  @Test
  void stockCannotDropBelowReserved() {
    var bowl = item(2, false, false);
    bowl.reserve(UUID.randomUUID(), UUID.randomUUID(), 2, NOW.plusSeconds(60), NOW);
    assertThatThrownBy(() -> bowl.adjustOnHand(1, NOW)).isInstanceOf(DomainRuleException.class);
    bowl.adjustOnHand(5, NOW);
    assertThat(bowl.available()).isEqualTo(3);
  }
}
