package io.hivecommerce.listing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

  @Test
  void operatorListingsPublishImmediately() {
    var listing = Listing.byOperator(UUID.randomUUID(), SCOPE, UUID.randomUUID(), NOW);
    listing.publish(NOW);
    listing.unpublish(NOW);
    listing.publish(NOW);
    assertThat(listing.status()).isEqualTo(Listing.Status.PUBLISHED);
  }

  @Test
  void sellerSubmissionsNeedApprovalBeforePublishing() {
    var listing = Listing.bySeller(UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), NOW);
    assertThatThrownBy(() -> listing.publish(NOW)).isInstanceOf(DomainRuleException.class);
    listing.reject("Missing certificate photo", NOW);
    assertThat(listing.rejectionReason()).isEqualTo("Missing certificate photo");
    listing.resubmit(NOW);
    listing.approve(NOW);
    listing.publish(NOW);
    assertThat(listing.status()).isEqualTo(Listing.Status.PUBLISHED);
    assertThat(listing.pullEvents()).extracting(e -> e.type()).contains("commerce.listing.ProductPublished");
  }
}
