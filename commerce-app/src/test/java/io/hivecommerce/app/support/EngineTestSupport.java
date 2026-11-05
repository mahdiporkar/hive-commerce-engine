package io.hivecommerce.app.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

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
  /** S3-compatible storage, pinned by digest (MinIO's own images are no longer pullable anonymously). */
  public static final GenericContainer<?> STORAGE = new GenericContainer<>(DockerImageName.parse(
      "cgr.dev/chainguard/minio@sha256:0dab2f9fee1c1139ae8753b5e35bb4461af2a276cfe0923a2504c75a6170bc64"))
      .withEnv("MINIO_ROOT_USER", "test-only-storage-user").withEnv("MINIO_ROOT_PASSWORD", "test-only-storage-password")
      .withCommand("server", "/tmp/data", "--address", ":9000").withExposedPorts(9000)
      .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

  static {
    POSTGRES.start();
    STORAGE.start();
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
    registry.add("commerce.storage.endpoint", EngineTestSupport::storageUrl);
    registry.add("commerce.storage.public-endpoint", EngineTestSupport::storageUrl);
    registry.add("commerce.storage.access-key", () -> "test-only-storage-user");
    registry.add("commerce.storage.secret-key", () -> "test-only-storage-password");
    registry.add("commerce.storage.bucket", () -> "commerce-test");
    registry.add("commerce.storage.create-bucket", () -> "true");
    registry.add("commerce.payments.provider", () -> "fake");
    registry.add("commerce.payments.fake.enabled", () -> "true");
    registry.add("commerce.payments.fake.webhook-secret", () -> FAKE_PSP_SECRET);
    registry.add("commerce.payments.return-url", () -> "https://shop.test/checkout/return");
  }

  public static String storageUrl() { return "http://" + STORAGE.getHost() + ":" + STORAGE.getMappedPort(9000); }

  /** Superuser connection for test setup that must bypass row-level security. */
  public static Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  public static Connection runtimeConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "commerce_app", RUNTIME_PASSWORD);
  }
}
