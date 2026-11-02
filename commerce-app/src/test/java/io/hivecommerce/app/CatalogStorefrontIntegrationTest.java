package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.CatalogFixtureLoader;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.events.OutboxDispatcher;
import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Categories, publishing and public browsing, the internal seller, outbox delivery, and Scenario D: a handicraft store
 * configured purely through the API from {@code fixtures/handicraft/catalog.json} (no handicraft code anywhere).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CatalogStorefrontIntegrationTest.Capture.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogStorefrontIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.events.dispatch-enabled", () -> "false");
    registry.add("commerce.store.key", () -> "storefront-store");
  }

  @TestConfiguration
  static class Capture {
    static final List<EventEnvelope> EVENTS = new CopyOnWriteArrayList<>();

    @Bean
    EventHandler captureCatalogEvents() {
      return new EventHandler() {
        public String consumer() { return "test.capture"; }
        public Set<String> eventTypes() { return Set.of("commerce.catalog.ProductCreated", "commerce.listing.ProductPublished"); }
        public void handle(EventEnvelope event) { EVENTS.add(event); }
      };
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired OutboxDispatcher dispatcher;
  @Autowired SellerDirectory sellers;
  @Autowired DeploymentScope deployment;
  @Autowired TenantTransactions transactions;
  ApiClient api;
  Caller operator;
  Map<String, String> ids;

  @BeforeAll
  void loadHandicraftStore() throws Exception {
    api = new ApiClient(port, json);
    operator = Caller.operator();
    ids = new CatalogFixtureLoader(api, json).load(Path.of("..", "fixtures", "handicraft", "catalog.json"), operator);
  }

  private JsonNode search(String query) {
    return api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products" + query, null).expect(200).body();
  }

  private static List<String> slugs(JsonNode page) {
    return StreamSupport.stream(page.get("items").spliterator(), false).map(i -> i.get("slug").asText()).sorted().toList();
  }

  @Test
  void scenarioD_handicraftStoreIsBrowsableByCategoryAndAttributes() {
    assertThat(search("?size=50").get("total").asInt()).isEqualTo(3);
    assertThat(slugs(search("?category=" + ids.get("category:pottery")))).containsExactly("lalejin-turquoise-bowl");
    assertThat(slugs(search("?f.material=wool,walnut"))).containsExactly("qashqai-gabbeh", "walnut-carved-box");
    assertThat(slugs(search("?f.customizable=true&max.preparation_days=30"))).containsExactly("walnut-carved-box");
    assertThat(slugs(search("?min.length=150"))).containsExactly("qashqai-gabbeh");
    assertThat(slugs(search("?f.color=natural"))).containsExactly("qashqai-gabbeh", "walnut-carved-box");
    assertThat(slugs(search("?q=" + "گبه"))).containsExactly("qashqai-gabbeh");
    var facets = search("").get("facets").toString();
    assertThat(facets).contains("\"key\":\"material\"").contains("\"value\":\"clay\"");
  }

  @Test
  void productDetailIsPublicAndUnpublishHidesIt() {
    var detail = api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/walnut-carved-box", null).expect(200).body();
    assertThat(detail.get("title").get("fa").asText()).isEqualTo("جعبه منبت گردو");
    assertThat(detail.get("variants")).hasSize(1);
    String listingId = detail.get("listingId").asText();
    api.call(operator, "admin-listing-unpublish", "POST", "/v1/admin/listings/" + listingId + "/unpublish", null).expect(200);
    assertThat(api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/walnut-carved-box", null).status()).isEqualTo(404);
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + ids.get("product:walnut-carved-box") + "/publish", null).expect(200);
    assertThat(api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/walnut-carved-box", null).status()).isEqualTo(200);
  }

  @Test
  void categoryTreeIsPublicAndArchivingRespectsChildren() {
    var tree = api.call(Caller.anonymous(), "store-categories", "GET", "/v1/store/categories", null).expect(200).body();
    assertThat(tree).hasSize(6);
    String pottery = ids.get("category:pottery"), bowls = ids.get("category:bowls");
    var blocked = api.call(operator, "admin-category-archive", "POST", "/v1/admin/categories/" + pottery + "/archive", Map.of("revision", 0));
    assertThat(blocked.status()).isEqualTo(409);
    assertThat(blocked.code()).isEqualTo("CATEGORY_HAS_CHILDREN");
    var moved = api.call(operator, "admin-category-move", "POST", "/v1/admin/categories/" + bowls + "/move",
        Map.of("parentId", ids.get("category:decorative"), "revision", 0)).expect(200).body();
    assertThat(moved.get("depth").asInt()).isEqualTo(2);
    assertThat(slugs(search("?category=" + ids.get("category:decorative")))).containsExactly("lalejin-turquoise-bowl");
    var cycle = api.call(operator, "admin-category-move", "POST", "/v1/admin/categories/" + ids.get("category:decorative") + "/move",
        Map.of("parentId", bowls, "revision", 0));
    assertThat(cycle.code()).isEqualTo("CATEGORY_CYCLE");
    api.call(operator, "admin-category-move", "POST", "/v1/admin/categories/" + bowls + "/move", Map.of("parentId", pottery, "revision", 1)).expect(200);
    assertThat(slugs(search("?category=" + pottery))).containsExactly("lalejin-turquoise-bowl");
  }

  @Test
  void publicSearchRejectsInvalidFilters() {
    assertThat(api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products?f.Bad-Key=x", null).status()).isEqualTo(422);
    assertThat(api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products?min.length=abc", null).status()).isEqualTo(422);
    assertThat(api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products?size=1000", null).status()).isEqualTo(422);
    assertThat(api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products?category=" + UUID.randomUUID(), null)
        .expect(200).body().get("total").asInt()).isZero();
  }

  @Test
  void internalSellerExistsAndOutboxEventsAreDelivered() {
    var internal = transactions.inScope(deployment.scope(), () -> sellers.seller(deployment.scope(), sellers.internalSellerId(deployment.scope())));
    assertThat(internal).hasValueSatisfying(s -> {
      assertThat(s.kind()).isEqualTo("INTERNAL");
      assertThat(s.status()).isEqualTo("ACTIVE");
    });
    dispatcher.dispatchAll();
    assertThat(Capture.EVENTS).extracting(EventEnvelope::type).contains("commerce.catalog.ProductCreated", "commerce.listing.ProductPublished");
    int delivered = Capture.EVENTS.size();
    dispatcher.dispatchAll();
    assertThat(Capture.EVENTS).hasSize(delivered);
  }
}
