package io.hivecommerce.listing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerOfferTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

  private static SellerOffer offer(boolean active, Money price, boolean unique, boolean digital, Integer quantity) {
    return SellerOffer.create(UUID.randomUUID(), SCOPE, UUID.randomUUID(), active, UUID.randomUUID(), UUID.randomUUID(), price, "IRR", null, unique, digital,
        quantity, null, NOW);
  }

  @Test
  void createsUniquePhysicalAndUnlimitedDigitalOffers() {
    var gem = offer(true, Money.of("850000000", "IRR"), true, false, 1);
    assertThat(gem.pullEvents()).singleElement().satisfies(e -> {
      assertThat(e.type()).isEqualTo("commerce.listing.OfferCreated");
      assertThat(e.payload()).containsEntry("unique", true).containsEntry("initialQuantity", 1);
    });
    var pdf = offer(true, Money.of("1200000", "IRR"), false, true, null);
    assertThat(pdf.pullEvents().getFirst().payload()).containsEntry("unlimited", true);
  }

  @Test
  void enforcesPriceCurrencySellerAndUniqueness() {
    assertThatThrownBy(() -> offer(false, Money.of("1", "IRR"), false, false, 1)).isInstanceOf(DomainRuleException.class);
    assertThatThrownBy(() -> offer(true, Money.of("1.00", "USD"), false, false, 1)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> offer(true, Money.of("0", "IRR"), false, false, 1)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> offer(true, Money.of("10", "IRR"), true, false, 2)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> offer(true, Money.of("10", "IRR"), false, false, null)).isInstanceOf(DomainValidationException.class);
  }

  @Test
  void withdrawnOffersCannotBeRepriced() {
    var o = offer(true, Money.of("10", "IRR"), false, false, 3);
    o.changePrice(Money.of("12", "IRR"), NOW);
    o.withdraw(NOW);
    assertThatThrownBy(() -> o.changePrice(Money.of("11", "IRR"), NOW)).isInstanceOf(DomainRuleException.class);
  }
}
