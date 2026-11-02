package io.hivecommerce.platform.guard;

import io.hiveplatform.spring.ProductionGuard;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in the production profile (the default, as in Hive) when a setting would weaken the trust
 * boundary (ADR-0005). {@code HIVE_PROFILE=development} downgrades violations to warnings for local work and tests.
 */
@Component
public class CommerceProductionGuard {
  private final Environment env;

  public CommerceProductionGuard(Environment env) {
    this.env = env;
  }

  @PostConstruct
  void enforce() {
    new ProductionGuard(env.getProperty("hive.profile", "production"))
        .rule("commerce.security.jwt.issuer must be an https URL", () -> !https(env.getProperty("commerce.security.jwt.issuer")))
        .rule("commerce.security.jwt.jwk-set-uri must be an https URL", () -> !https(env.getProperty("commerce.security.jwt.jwk-set-uri")))
        .rule("commerce.security.jwt.audience is required", () -> blank(env.getProperty("commerce.security.jwt.audience")))
        .rule("commerce.hive.enforce-operation must stay enabled", () -> !env.getProperty("commerce.hive.enforce-operation", Boolean.class, true))
        .rule("the FAKE payment provider must not be enabled", () -> env.getProperty("commerce.payments.fake.enabled", Boolean.class, false))
        .rule("commerce.payments.return-url must be an https URL when a payment provider is configured",
            () -> !blank(env.getProperty("commerce.payments.provider")) && !https(env.getProperty("commerce.payments.return-url")))
        .rule("the runtime database password must be at least 16 characters", () -> env.getProperty("spring.datasource.password", "").length() < 16)
        .rule("the migration database password must be at least 16 characters", () -> env.getProperty("commerce.migration.password", "").length() < 16)
        .enforce("commerce-engine");
  }

  private static boolean https(String value) {
    if (blank(value)) return false;
    try {
      return "https".equals(URI.create(value).getScheme());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
