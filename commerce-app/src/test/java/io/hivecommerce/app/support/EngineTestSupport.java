package io.hivecommerce.app.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container and one identity provider for all engine integration tests. The container's superuser
 * plays the migration owner; {@code commerce_app} is a plain role that cannot bypass row-level security.
 */
public final class EngineTestSupport {
  public static final String FAKE_PSP_SECRET = "test-only-fake-psp-secret-0001";
  public static final String RUNTIME_PASSWORD = "test-only-runtime-password-0001";
  public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
      .withDatabaseName("commerce").withUsername("commerce_owner").withPassword("test-only-owner-password-0001")
      .withInitScript("test-roles.sql");
  public static final TestIdentityProvider IDP = new TestIdentityProvider();

  static {
    POSTGRES.start();
  }

  private EngineTestSupport() {}

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("hive.profile", () -> "development");
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    registry.add("commerce.migration.url", POSTGRES::getJdbcUrl);
    registry.add("commerce.migration.username", POSTGRES::getUsername);
    registry.add("commerce.migration.password", POSTGRES::getPassword);
    registry.add("commerce.migration.runtime-role", () -> "commerce_app");
    registry.add("commerce.tenant-id", () -> "default");
    registry.add("commerce.store.key", () -> "test-store");
    registry.add("commerce.security.jwt.issuer", () -> TestIdentityProvider.ISSUER);
    registry.add("commerce.security.jwt.jwk-set-uri", IDP::jwksUri);
    registry.add("commerce.payments.provider", () -> "fake");
    registry.add("commerce.payments.fake.enabled", () -> "true");
    registry.add("commerce.payments.fake.webhook-secret", () -> FAKE_PSP_SECRET);
    registry.add("commerce.payments.return-url", () -> "https://shop.test/checkout/return");
  }

  /** Superuser connection for test setup that must bypass row-level security. */
  public static Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  public static Connection runtimeConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "commerce_app", RUNTIME_PASSWORD);
  }
}
