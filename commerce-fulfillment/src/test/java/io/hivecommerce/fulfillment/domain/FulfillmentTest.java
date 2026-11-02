package io.hivecommerce.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FulfillmentTest {
  @Test
  void shipsThenDeliversInOrder() {
    var f = Fulfillment.open(UUID.randomUUID(), new TenantScope("default", UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    var now = Instant.parse("2026-11-02T10:00:00Z");
    assertThatThrownBy(() -> f.confirmDelivery(now)).isInstanceOf(DomainRuleException.class);
    f.ship("Post", "IR123", now);
    assertThatThrownBy(() -> f.ship("Post", "IR124", now)).isInstanceOf(DomainRuleException.class);
    f.confirmDelivery(now);
    assertThat(f.status()).isEqualTo(Fulfillment.Status.DELIVERED);
    assertThat(f.pullEvents()).extracting(e -> e.type()).containsExactly("commerce.fulfillment.SellerOrderShipped", "commerce.fulfillment.SellerOrderDelivered");
  }
}
