package io.hivecommerce.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  private final UUID owner = UUID.randomUUID();

  private Seller active() {
    var seller = Seller.apply(UUID.randomUUID(), SCOPE, owner, LocalizedText.of("en", "Gem House"), NOW);
    seller.verify(NOW);
    seller.approve(NOW);
    return seller;
  }

  @Test
  void onboardingActivatesTheOwnerOnlyAfterApproval() {
    var seller = Seller.apply(UUID.randomUUID(), SCOPE, owner, LocalizedText.of("en", "Gem House"), NOW);
    assertThat(seller.allows(owner, "offers.manage")).isFalse();
    assertThatThrownBy(() -> seller.approve(NOW)).isInstanceOf(DomainRuleException.class);
    seller.verify(NOW);
    seller.approve(NOW);
    assertThat(seller.status()).isEqualTo(Seller.Status.ACTIVE);
    assertThat(seller.allows(owner, "offers.manage")).isTrue();
    assertThat(seller.pullEvents()).extracting(e -> e.type()).containsExactly("commerce.seller.SellerApplied", "commerce.seller.SellerVerified", "commerce.seller.SellerApproved");
  }

  @Test
  void staffCapabilitiesAreScopedAndSuspensionRemovesAccess() {
    var seller = active();
    UUID staff = UUID.randomUUID();
    seller.grantMembership(staff, Seller.Role.STAFF, Set.of("orders.view"), NOW);
    assertThat(seller.allows(staff, "orders.view")).isTrue();
    assertThat(seller.allows(staff, "finance.view")).isFalse();
    assertThat(seller.allows(UUID.randomUUID(), "orders.view")).isFalse();
    seller.suspend(NOW);
    assertThat(seller.allows(owner, "orders.view")).isFalse();
  }

  @Test
  void keepsAtLeastOneOwner() {
    var seller = active();
    assertThatThrownBy(() -> seller.revokeMembership(owner, NOW)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("LAST_OWNER"));
    UUID second = UUID.randomUUID();
    seller.grantMembership(second, Seller.Role.OWNER, Set.of(), NOW);
    seller.revokeMembership(owner, NOW);
    assertThat(seller.allows(owner, "orders.view")).isFalse();
    assertThat(seller.allows(second, "staff.manage")).isTrue();
  }

  @Test
  void internalSellerIsProtectedAndDeterministic() {
    var internal = Seller.internal(SCOPE, LocalizedText.of("en", "Store"));
    assertThat(internal.id()).isEqualTo(Seller.internalIdFor(SCOPE));
    assertThat(internal.status()).isEqualTo(Seller.Status.ACTIVE);
    assertThatThrownBy(() -> internal.suspend(NOW)).isInstanceOf(DomainRuleException.class);
  }
}
