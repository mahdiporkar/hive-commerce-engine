package io.hivecommerce.platform.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.spi.CommerceExtension;
import io.hivecommerce.spi.Issue;
import io.hivecommerce.spi.ProductFacts;
import io.hivecommerce.spi.ProductValidationExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtensionRegistryTest {
  private static final ProductFacts FACTS = new ProductFacts("t", "PHYSICAL", Map.of(), List.of());

  private static ProductValidationExtension validator(String key, java.util.function.Supplier<List<Issue>> result) {
    return new ProductValidationExtension() {
      public String key() { return key; }
      public List<Issue> validate(ProductFacts product) { return result.get(); }
    };
  }

  @Test
  void anEngineWithoutExtensionsAcceptsEverything() {
    new ExtensionRegistry(null, List.of()).validateProduct(FACTS);
  }

  @Test
  void issuesRejectAndExceptionsFailClosed() {
    var issues = new ExtensionRegistry(null, List.of(validator("a", () -> List.of(new Issue("attributes.x", "bad")))));
    assertThatThrownBy(() -> issues.validateProduct(FACTS)).isInstanceOf(DomainValidationException.class);
    var broken = new ExtensionRegistry(null, List.of(validator("b", () -> { throw new IllegalStateException("boom"); })));
    assertThatThrownBy(() -> broken.validateProduct(FACTS)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("EXTENSION_REJECTED"));
  }

  @Test
  void refusesAnotherSpiMajorAndDuplicateKeys() {
    CommerceExtension future = new CommerceExtension() {
      public String key() { return "future"; }
      public int spiMajor() { return 2; }
    };
    assertThatThrownBy(() -> new ExtensionRegistry(null, List.of(future))).hasMessageContaining("SPI major 2");
    assertThatThrownBy(() -> new ExtensionRegistry(null, List.of(validator("x", List::of), validator("x", List::of)))).hasMessageContaining("Duplicate");
  }
}
