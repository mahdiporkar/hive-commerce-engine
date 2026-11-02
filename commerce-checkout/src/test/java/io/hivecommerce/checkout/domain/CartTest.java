package io.hivecommerce.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());

  @Test
  void mergesQuantitiesAndCapsUniqueItems() {
    var cart = Cart.forCustomer(UUID.randomUUID(), SCOPE, UUID.randomUUID());
    UUID bowl = UUID.randomUUID(), gem = UUID.randomUUID();
    cart.add(UUID.randomUUID(), bowl, 2, false);
    cart.add(UUID.randomUUID(), bowl, 3, false);
    cart.add(UUID.randomUUID(), gem, 1, true);
    cart.add(UUID.randomUUID(), gem, 1, true);
    assertThat(cart.items()).extracting(Cart.Item::quantity).containsExactly(5, 1);
    assertThatThrownBy(() -> cart.add(UUID.randomUUID(), UUID.randomUUID(), 2, true)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> cart.add(UUID.randomUUID(), UUID.randomUUID(), 0, false)).isInstanceOf(DomainValidationException.class);
  }

  @Test
  void customerCartAbsorbsAGuestCartOnce() {
    var guest = Cart.forGuest(UUID.randomUUID(), SCOPE, "h".repeat(64), Instant.now().plusSeconds(60));
    UUID gem = UUID.randomUUID();
    guest.add(UUID.randomUUID(), gem, 1, true);
    var customer = Cart.forCustomer(UUID.randomUUID(), SCOPE, UUID.randomUUID());
    customer.add(UUID.randomUUID(), gem, 1, true);
    customer.absorb(guest, UUID::randomUUID);
    assertThat(customer.items()).hasSize(1);
    assertThat(guest.status()).isEqualTo(Cart.Status.MERGED);
    assertThatThrownBy(() -> customer.absorb(guest, UUID::randomUUID)).isInstanceOf(DomainRuleException.class);
    customer.checkedOut();
    assertThatThrownBy(() -> customer.add(UUID.randomUUID(), gem, 1, true)).isInstanceOf(DomainRuleException.class);
  }
}
