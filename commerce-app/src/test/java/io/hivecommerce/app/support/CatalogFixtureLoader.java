package io.hivecommerce.app.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hivecommerce.app.support.ApiClient.Caller;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads a store taxonomy fixture (attributes, product types, categories, products) through the public engine API only,
 * then publishes the products. Generic: knows nothing about any industry. Client repositories seed the same way.
 */
public final class CatalogFixtureLoader {
  private final ApiClient api;
  private final ObjectMapper json;

  public CatalogFixtureLoader(ApiClient api, ObjectMapper json) {
    this.api = api;
    this.json = json;
  }

  /** @return product slug → product id, and category ref → category id */
  public Map<String, String> load(Path fixture, Caller operator) throws IOException {
    JsonNode root = json.readTree(fixture.toFile());
    var ids = new HashMap<String, String>();
    for (JsonNode attribute : root.get("attributes")) api.call(operator, "admin-attribute-define", "POST", "/v1/admin/attributes", attribute).expect(201);
    for (JsonNode type : root.get("productTypes")) {
      api.call(operator, "admin-product-type-create", "POST", "/v1/admin/product-types", type).expect(201);
      api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/" + type.get("key").asText() + "/versions/1/publish", null).expect(200);
    }
    for (JsonNode category : root.get("categories")) {
      ObjectNode body = category.deepCopy();
      body.remove("ref");
      if (body.has("parent")) body.put("parentId", ids.get("category:" + body.remove("parent").asText()));
      var created = api.call(operator, "admin-category-create", "POST", "/v1/admin/categories", body).expect(201);
      ids.put("category:" + category.get("ref").asText(), created.body().get("id").asText());
    }
    for (JsonNode product : root.get("products")) {
      ObjectNode body = product.deepCopy();
      body.put("categoryId", ids.get("category:" + body.remove("category").asText()));
      var created = api.call(operator, "admin-product-create", "POST", "/v1/admin/products", body).expect(201);
      String id = created.body().get("id").asText();
      ids.put("product:" + product.get("slug").asText(), id);
      api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + id + "/publish", null).expect(200);
    }
    return ids;
  }
}
