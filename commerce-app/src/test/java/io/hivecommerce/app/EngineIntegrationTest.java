package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.app.support.TestIdentityProvider;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Engine-level integration tests: real PostgreSQL (row-level security, runtime role without ownership), real JWT
 * validation against a JWKS endpoint, and the Hive headers exactly as the BFF forwards them. Layer 1 (Hive deciding
 * resource actions) is covered by the through-BFF suite in tests/e2e.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineIntegrationTest {
  private static final TestIdentityProvider IDP = EngineTestSupport.IDP;
  private static final String TENANT = "default";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) { EngineTestSupport.register(registry); }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  RestClient http;

  record Response(int status, JsonNode body) {
    String code() { return body == null || body.get("code") == null ? null : body.get("code").asText(); }
  }

  /** A caller as the BFF would present it: user token + Hive identity + route operation. */
  record Caller(String token, UUID hiveUserId, String tenant) {}

  static Caller operator() {
    return new Caller(IDP.token("kc-" + UUID.randomUUID()), UUID.randomUUID(), TENANT);
  }

  @BeforeEach
  void client() {
    http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (req, res) -> {}).build();
  }

  Response call(String method, String path, Caller caller, String operation, Object body, Map<String, String> extraHeaders) {
    var request = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON);
    if (caller != null) {
      if (caller.token() != null) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
      if (caller.hiveUserId() != null) request = request.header("X-Hive-User-Id", caller.hiveUserId().toString());
      if (caller.tenant() != null) request = request.header("X-Hive-Tenant-Id", caller.tenant());
    }
    if (operation != null) request = request.header("X-Hive-Route", "commerce").header("X-Hive-Operation", operation);
    for (var header : extraHeaders.entrySet()) request = request.header(header.getKey(), header.getValue());
    if (body != null) request = request.body(body);
    return request.exchange((req, res) -> {
      byte[] bytes = res.getBody().readAllBytes();
      return new Response(res.getStatusCode().value(), bytes.length == 0 ? null : json.readTree(bytes));
    });
  }

  Response call(String method, String path, Caller caller, String operation, Object body) {
    return call(method, path, caller, operation, body, Map.of());
  }

  /** Defines the generic vocabulary and a published product type; returns its key. */
  String publishedProductType(Caller op) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String material = "material_" + suffix, color = "color_" + suffix, type = "type-" + suffix;
    assertThat(call("POST", "/v1/admin/attributes", op, "admin-attribute-define", Map.of("key", material, "type", "ENUM", "labels", Map.of("en", "Material", "fa", "جنس"),
        "options", List.of(Map.of("code", "wood", "labels", Map.of("en", "Wood")), Map.of("code", "clay", "labels", Map.of("en", "Clay"))))).status()).isEqualTo(201);
    assertThat(call("POST", "/v1/admin/attributes", op, "admin-attribute-define", Map.of("key", color, "type", "COLOR", "labels", Map.of("en", "Color"),
        "options", List.of(Map.of("code", "red", "labels", Map.of("en", "Red")), Map.of("code", "blue", "labels", Map.of("en", "Blue"))))).status()).isEqualTo(201);
    var created = call("POST", "/v1/admin/product-types", op, "admin-product-type-create", Map.of("key", type, "name", Map.of("en", "Generic"),
        "attributes", List.of(Map.of("key", material, "required", true, "variantDefining", false), Map.of("key", color, "required", true, "variantDefining", true))));
    assertThat(created.status()).isEqualTo(201);
    assertThat(call("POST", "/v1/admin/product-types/" + type + "/versions/1/publish", op, "admin-product-type-publish", null).status()).isEqualTo(200);
    return type + "|" + material + "|" + color;
  }

  Map<String, Object> productBody(String typeSpec, String sku) {
    String[] parts = typeSpec.split("\\|");
    var body = new LinkedHashMap<String, Object>();
    body.put("productType", parts[0]);
    body.put("title", Map.of("en", "Bowl", "fa", "کاسه"));
    body.put("slug", "bowl-" + sku.toLowerCase());
    body.put("attributes", Map.of(parts[1], "clay"));
    body.put("variants", List.of(Map.of("sku", sku + "-R", "attributes", Map.of(parts[2], "red")), Map.of("sku", sku + "-B", "attributes", Map.of(parts[2], "blue"))));
    return body;
  }

  @Nested
  class AllowedOperator {
    @Test
    void createsAndReadsAProductWithOutboxEvents() throws SQLException {
      var op = operator();
      String type = publishedProductType(op);
      String sku = "SKU" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
      var created = call("POST", "/v1/admin/products", op, "admin-product-create", productBody(type, sku));
      assertThat(created.status()).isEqualTo(201);
      assertThat(created.body().get("variants")).hasSize(2);
      assertThat(created.body().get("schemaVersion").asInt()).isEqualTo(1);
      String id = created.body().get("id").asText();

      var read = call("GET", "/v1/admin/products/" + id, op, "admin-product-get", null);
      assertThat(read.status()).isEqualTo(200);
      assertThat(read.body().get("title").get("fa").asText()).isEqualTo("کاسه");

      try (var c = EngineTestSupport.ownerConnection(); var rs = c.createStatement().executeQuery(
          "select event_type from catalog.outbox_event where aggregate_id = '" + id + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("commerce.catalog.ProductCreated");
      }
    }

    @Test
    void reportsValidationErrorsAndConflicts() {
      var op = operator();
      String type = publishedProductType(op);
      var invalid = productBody(type, "X1");
      invalid.put("attributes", Map.of("unknown", 1));
      var rejected = call("POST", "/v1/admin/products", op, "admin-product-create", invalid);
      assertThat(rejected.status()).isEqualTo(422);
      assertThat(rejected.code()).isEqualTo("VALIDATION_FAILED");
      assertThat(rejected.body().get("violations").toString()).contains("attributes.unknown");

      String sku = "DUP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
      assertThat(call("POST", "/v1/admin/products", op, "admin-product-create", productBody(type, sku)).status()).isEqualTo(201);
      var duplicate = productBody(type, sku);
      duplicate.put("slug", "other-" + sku.toLowerCase());
      var conflict = call("POST", "/v1/admin/products", op, "admin-product-create", duplicate);
      assertThat(conflict.status()).isEqualTo(409);
      assertThat(conflict.code()).isEqualTo("SKU_TAKEN");

      var republish = call("POST", "/v1/admin/product-types/" + type.split("\\|")[0] + "/versions/1/publish", op, "admin-product-type-publish", null);
      assertThat(republish.status()).isEqualTo(409);
      assertThat(republish.code()).isEqualTo("SCHEMA_VERSION_PUBLISHED");
    }
  }

  @Nested
  class DeniedCallers {
    @Test
    void anonymousCallerIsRejected() {
      var response = call("POST", "/v1/admin/products", null, "admin-product-create", Map.of());
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void forgedHiveUserIdWithoutTokenIsRejectedEverywhere() {
      var forged = new Caller(null, UUID.randomUUID(), TENANT);
      assertThat(call("POST", "/v1/admin/products", forged, "admin-product-create", Map.of()).code()).isEqualTo("IDENTITY_UNVERIFIED");
      var nonAdmin = call("GET", "/v1/store/products/anything", forged, null, null);
      assertThat(nonAdmin.status()).isEqualTo(401);
      assertThat(nonAdmin.code()).isEqualTo("IDENTITY_UNVERIFIED");
    }

    @Test
    void invalidTokensAreRejected() {
      UUID user = UUID.randomUUID();
      for (String token : List.of(
          IDP.token("kc-x", TestIdentityProvider.ISSUER, "some-other-api", Instant.now().plusSeconds(300), false),
          IDP.token("kc-x", "https://evil.test/realms/commerce", TestIdentityProvider.AUDIENCE, Instant.now().plusSeconds(300), false),
          IDP.token("kc-x", TestIdentityProvider.ISSUER, TestIdentityProvider.AUDIENCE, Instant.now().minusSeconds(120), false),
          IDP.token("kc-x", TestIdentityProvider.ISSUER, TestIdentityProvider.AUDIENCE, Instant.now().plusSeconds(300), true),
          "not-a-jwt")) {
        var response = call("POST", "/v1/admin/products", new Caller(token, user, TENANT), "admin-product-create", Map.of());
        assertThat(response.status()).as("token %s", token.substring(0, Math.min(12, token.length()))).isEqualTo(401);
        assertThat(response.code()).isEqualTo("TOKEN_INVALID");
      }
    }

    @Test
    void tokenWithoutForwardedIdentityIsRejected() {
      var response = call("POST", "/v1/admin/products", new Caller(IDP.token("kc-y"), null, null), "admin-product-create", Map.of());
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.code()).isEqualTo("IDENTITY_INCOMPLETE");
    }

    @Test
    void identityOfAnotherTenantIsRejected() {
      var response = call("POST", "/v1/admin/products", new Caller(IDP.token("kc-z"), UUID.randomUUID(), "other-tenant"), "admin-product-create", Map.of());
      assertThat(response.status()).isEqualTo(403);
      assertThat(response.code()).isEqualTo("TENANT_MISMATCH");
    }

    @Test
    void forwardedUserMustMatchTheTokensRecordedBinding() throws SQLException {
      String subject = "kc-" + UUID.randomUUID();
      String token = IDP.token(subject);
      var first = new Caller(token, UUID.randomUUID(), TENANT);
      assertThat(call("GET", "/v1/admin/products/" + UUID.randomUUID(), first, "admin-product-get", null).status()).isEqualTo(404);
      var swapped = call("GET", "/v1/admin/products/" + UUID.randomUUID(), new Caller(token, UUID.randomUUID(), TENANT), "admin-product-get", null);
      assertThat(swapped.status()).isEqualTo(401);
      assertThat(swapped.code()).isEqualTo("IDENTITY_BINDING_MISMATCH");
      try (var c = EngineTestSupport.ownerConnection(); var rs = c.createStatement().executeQuery(
          "select count(*) from platform.security_event where event_type = 'identity.binding.mismatch' and subject = '" + subject + "'")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }

    @Test
    void operatorHandlerRefusesAnotherRouteOperation() {
      var op = operator();
      var wrongOperation = call("POST", "/v1/admin/products", op, "admin-product-get", Map.of());
      assertThat(wrongOperation.status()).isEqualTo(403);
      assertThat(wrongOperation.code()).isEqualTo("ROUTE_OPERATION_MISMATCH");
      var noOperation = call("POST", "/v1/admin/products", op, null, Map.of());
      assertThat(noOperation.status()).isEqualTo(403);
      assertThat(noOperation.code()).isEqualTo("ROUTE_OPERATION_MISMATCH");
    }
  }

  @Nested
  class TenantIsolation {
    @Test
    void productsOfAnotherStoreAreInvisibleEvenWithTheirId() throws SQLException {
      var op = operator();
      String type = publishedProductType(op);
      var created = call("POST", "/v1/admin/products", op, "admin-product-create", productBody(type, "ISO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()));
      UUID ownId = UUID.fromString(created.body().get("id").asText());
      UUID foreignId = UUID.randomUUID();
      try (var c = EngineTestSupport.ownerConnection(); var s = c.createStatement()) {
        // A row of another store, copied from ours: same tenant, different store_id (superuser bypasses RLS here).
        s.executeUpdate("insert into catalog.product(id, tenant_id, store_id, product_type_id, schema_version, title, slug, attributes, status) "
            + "select '" + foreignId + "', tenant_id, '" + UUID.randomUUID() + "', product_type_id, schema_version, title, 'foreign-" + foreignId + "', attributes, status "
            + "from catalog.product where id = '" + ownId + "'");
      }
      var foreign = call("GET", "/v1/admin/products/" + foreignId, op, "admin-product-get", null);
      assertThat(foreign.status()).isEqualTo(404);
      assertThat(call("GET", "/v1/admin/products/" + ownId, op, "admin-product-get", null).status()).isEqualTo(200);
    }

    @Test
    void runtimeRoleSeesNothingWithoutScopeAndCannotDelete() throws SQLException {
      try (var c = EngineTestSupport.runtimeConnection(); var s = c.createStatement()) {
        assertThatThrownBy(() -> s.executeQuery("select count(*) from catalog.product")).isInstanceOf(SQLException.class)
            .hasMessageContaining("app.tenant_id");
        c.setAutoCommit(false);
        s.execute("select set_config('app.tenant_id', 'default', true), set_config('app.store_id', '" + UUID.randomUUID() + "', true)");
        try (var rs = s.executeQuery("select count(*) from catalog.product")) {
          rs.next();
          assertThat(rs.getInt(1)).as("a store with no data sees no rows").isZero();
        }
        assertThatThrownBy(() -> s.executeUpdate("delete from catalog.product")).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        c.rollback();
      }
    }

    @Test
    void publishedSchemaVersionsAreImmutableInTheDatabase() throws SQLException {
      var op = operator();
      String type = publishedProductType(op).split("\\|")[0];
      try (var c = EngineTestSupport.ownerConnection(); var s = c.createStatement()) {
        assertThatThrownBy(() -> s.executeUpdate("update catalog.product_type_schema_version set specs = '[]' where product_type_id = "
            + "(select id from catalog.product_type where key = '" + type + "')")).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
      }
    }
  }
}
