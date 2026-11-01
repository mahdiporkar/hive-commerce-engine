package io.hivecommerce.store.application;

import io.hivecommerce.store.domain.CommerceMode;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The deployment's store, from client configuration (ADR-0014). The tenant must match the Hive tenant of operators. */
@ConfigurationProperties("commerce")
public record StoreSettings(@DefaultValue("default") String tenantId, Store store) {
  public record Store(String key, @DefaultValue("SINGLE_VENDOR") CommerceMode mode, String defaultCurrency, List<String> locales) {}
}
