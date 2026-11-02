package io.hivecommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CommerceConfigurationValidatorTest {
  private static MockEnvironment valid() {
    return new MockEnvironment()
        .withProperty("commerce.tenant-id", "default")
        .withProperty("commerce.store.key", "aidastoor")
        .withProperty("commerce.store.mode", "MULTI_VENDOR")
        .withProperty("commerce.store.default-currency", "IRR")
        .withProperty("commerce.store.locales", "fa,en")
        .withProperty("commerce.hive.route-key", "commerce")
        .withProperty("commerce.capabilities.digital-products", "true")
        .withProperty("commerce.security.jwt.issuer", "https://id.example.org/realms/aidastoor")
        .withProperty("commerce.security.jwt.audience", "commerce-engine");
  }

  @Test
  void acceptsAValidConfiguration() {
    var validator = new CommerceConfigurationValidator(valid(), new ObjectMapper());
    assertThat(validator.validate(validator.document())).isEmpty();
  }

  @Test
  void listsEveryViolationAndRefusesToStart() {
    var env = valid().withProperty("commerce.store.mode", "MARKETPLACE").withProperty("commerce.store.default-currency", "rial")
        .withProperty("commerce.store.locales", "persian").withProperty("commerce.capabilities.commissions", "maybe");
    var validator = new CommerceConfigurationValidator(env, new ObjectMapper());
    assertThat(validator.validate(validator.document())).hasSize(4);
    assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid commerce configuration");
  }
}
