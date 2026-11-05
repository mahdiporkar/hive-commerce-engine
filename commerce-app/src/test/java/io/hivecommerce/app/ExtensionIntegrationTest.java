package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.EngineTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A client-style extension loaded through the SPI adds rules and public data without any change to core code. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtensionIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.store.key", () -> "extension-store");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;

  @Test
  void extensionValidatesGuardsPublicationAndPresentsData() {
    var api = new ApiClient(port, json);
    var operator = Caller.operator();
    api.call(operator, "admin-attribute-define", "POST", "/v1/admin/attributes", Map.of("key", "note", "type", "STRING", "labels", Map.of("en", "Note"))).expect(201);
    for (String type : List.of("guarded-item", "guarded-boom", "plain-item")) {
      api.call(operator, "admin-product-type-create", "POST", "/v1/admin/product-types", Map.of("key", type, "name", Map.of("en", type), "attributes", List.of(Map.of("key", "note", "required", false)))).expect(201);
      api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/" + type + "/versions/1/publish", null).expect(200);
    }
    // Product validation: reported issues reject; an extension failure fails closed; other types are untouched.
    var refused = api.call(operator, "admin-product-create", "POST", "/v1/admin/products", product("guarded-item", "refused", "forbidden"));
    assertThat(refused.status()).isEqualTo(422);
    assertThat(refused.body().get("violations").toString()).contains("attributes.note");
    assertThat(api.call(operator, "admin-product-create", "POST", "/v1/admin/products", product("guarded-boom", "boom", "x")).code()).isEqualTo("EXTENSION_REJECTED");
    String plain = api.call(operator, "admin-product-create", "POST", "/v1/admin/products", product("plain-item", "plain", "forbidden")).expect(201).body().get("id").asText();
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + plain + "/publish", null).expect(200);

    // Publication waits for the extension's inspection record, written through its validated document API.
    String guarded = api.call(operator, "admin-product-create", "POST", "/v1/admin/products", product("guarded-item", "guarded", "ok")).expect(201).body().get("id").asText();
    assertThat(api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + guarded + "/publish", null).status()).isEqualTo(422);
    String document = "/v1/admin/extensions/test-guard/product/" + guarded;
    assertThat(api.call(operator, "admin-extension-put", "PUT", document, Map.of("other", 1)).status()).isEqualTo(422);
    assertThat(api.call(operator, "admin-extension-put", "PUT", "/v1/admin/extensions/unknown/product/" + guarded, Map.of("inspection", "PASSED")).code()).isEqualTo("EXTENSION_UNKNOWN");
    api.call(operator, "admin-extension-put", "PUT", document, Map.of("inspection", "PASSED")).expect(200);
    assertThat(api.call(operator, "admin-extension-get", "GET", document, null).expect(200).body().get("inspection").asText()).isEqualTo("PASSED");
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + guarded + "/publish", null).expect(200);

    // The public page carries the extension's block; a plain product has none.
    var detail = api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/guarded", null).expect(200).body();
    assertThat(detail.get("extensions").get("test-guard").get("inspection").asText()).isEqualTo("PASSED");
    assertThat(api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/plain", null).expect(200).body().get("extensions")).isEmpty();
  }

  private static Map<String, Object> product(String type, String slug, String note) {
    return Map.of("productType", type, "title", Map.of("en", slug), "slug", slug, "attributes", Map.of("note", note),
        "variants", List.of(Map.of("sku", slug.toUpperCase(), "attributes", Map.of())));
  }
}
