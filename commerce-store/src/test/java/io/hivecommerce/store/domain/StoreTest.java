package io.hivecommerce.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreTest {
  @Test
  void storeIdIsStablePerTenantAndKey() {
    var a = Store.bootstrap("default", "gemstone", CommerceMode.SINGLE_VENDOR, "IRR", List.of("fa", "en"));
    var b = Store.bootstrap("default", "gemstone", CommerceMode.SINGLE_VENDOR, "IRR", List.of("fa"));
    var other = Store.bootstrap("other", "gemstone", CommerceMode.SINGLE_VENDOR, "IRR", List.of("fa"));
    assertThat(a.scope().storeId()).isEqualTo(b.scope().storeId()).isNotEqualTo(other.scope().storeId());
  }

  @Test
  void rejectsInvalidConfigurationWithAllViolations() {
    assertThatThrownBy(() -> Store.bootstrap("default", "Bad Key", null, "ZZZ", List.of("persian")))
        .isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.violations()).hasSize(4));
  }

  @Test
  void upgradesFreelyButBlocksDowngradeWithExternalSellers() {
    var store = Store.bootstrap("default", "shop", CommerceMode.SINGLE_VENDOR, "IRR", List.of("fa"));
    store.changeMode(CommerceMode.MULTI_VENDOR, false);
    assertThat(store.mode()).isEqualTo(CommerceMode.MULTI_VENDOR);
    assertThatThrownBy(() -> store.changeMode(CommerceMode.SINGLE_VENDOR, true)).isInstanceOf(DomainRuleException.class);
    store.changeMode(CommerceMode.SINGLE_VENDOR, false);
    assertThat(store.mode()).isEqualTo(CommerceMode.SINGLE_VENDOR);
  }
}
