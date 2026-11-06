package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.platform.events.OutboxDispatcher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Engine 0.2.0 against real PostgreSQL and storage: explicit schema evolution, public media and category metadata,
 * verified-purchase reviews, operator views of customers and payments, and the seller's own asset list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseTwoIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.store.key", () -> "release-two");
    registry.add("commerce.store.mode", () -> "MULTI_VENDOR");
    registry.add("commerce.capabilities.seller-onboarding", () -> "true");
    registry.add("commerce.capabilities.commissions", () -> "true");
    registry.add("commerce.capabilities.reviews", () -> "true");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired OutboxDispatcher dispatcher;
  ApiClient api;
  RestClient hooks;
  final Caller operator = Caller.operator();

  @BeforeAll
  void setUp() {
    api = new ApiClient(port, json);
    hooks = RestClient.create("http://127.0.0.1:" + port);
    api.call(operator, "admin-attribute-define", "POST", "/v1/admin/attributes", Map.of("key", "note", "type", "STRING", "labels", Map.of("en", "Note"))).expect(201);
    api.call(operator, "admin-attribute-define", "POST", "/v1/admin/attributes", Map.of("key", "grade", "type", "ENUM", "labels", Map.of("en", "Grade"),
        "options", List.of(Map.of("code", "a", "labels", Map.of("en", "A"))), "flags", Map.of("filterable", true, "searchable", false, "sortable", false, "comparable", false))).expect(201);
  }

  private void type(String key, String kind) {
    api.call(operator, "admin-product-type-create", "POST", "/v1/admin/product-types", Map.of("key", key, "name", Map.of("en", key), "kind", kind, "attributes", List.of(Map.of("key", "note", "required", false, "variantDefining", false)))).expect(201);
    api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/" + key + "/versions/1/publish", null).expect(200);
  }

  private JsonNode product(String type, String slug) {
    return api.call(operator, "admin-product-create", "POST", "/v1/admin/products", Map.of("productType", type, "title", Map.of("en", slug), "slug", slug,
        "attributes", Map.of("note", "x"), "variants", List.of(Map.of("sku", "SKU-" + slug, "attributes", Map.of())))).expect(201).body();
  }

  @Test
  void aSchemaChangeIsANewVersionAndProductsMigrateExplicitly() {
    type("evolving", "PHYSICAL");
    var existing = product("evolving", "evolving-one");
    String id = existing.get("id").asText();
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + id + "/publish", null).expect(200);
    dispatcher.dispatchAll();

    var v2 = List.of(Map.of("key", "note", "required", false, "variantDefining", false), Map.of("key", "grade", "required", true, "variantDefining", false));
    var drafted = api.call(operator, "admin-product-type-draft", "POST", "/v1/admin/product-types/evolving/versions", Map.of("attributes", v2)).expect(201).body();
    assertThat(drafted.get("versions").size()).isEqualTo(2);
    assertThat(api.call(operator, "admin-product-type-draft", "POST", "/v1/admin/product-types/evolving/versions", Map.of("attributes", v2)).code()).isEqualTo("DRAFT_EXISTS");

    // Drafting changes nothing for existing products, and migrating before publication is refused.
    assertThat(api.call(operator, "admin-product-migrate", "POST", "/v1/admin/products/" + id + "/schema-migration", Map.of("revision", existing.get("revision").asLong())).code()).isEqualTo("SCHEMA_CURRENT");
    api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/evolving/versions/2/publish", null).expect(200);
    var unchanged = api.call(operator, "admin-product-get", "GET", "/v1/admin/products/" + id, null).expect(200).body();
    assertThat(unchanged.get("schemaVersion").asInt()).as("never silently migrated").isEqualTo(1);

    // The new version requires a value this product lacks: the migration says so instead of breaking it.
    var missing = api.call(operator, "admin-product-migrate", "POST", "/v1/admin/products/" + id + "/schema-migration", Map.of("revision", unchanged.get("revision").asLong()));
    assertThat(missing.status()).isEqualTo(422);
    assertThat(missing.body().get("violations").toString()).contains("grade");
    assertThat(api.call(operator, "admin-product-get", "GET", "/v1/admin/products/" + id, null).body().get("schemaVersion").asInt()).isEqualTo(1);

    var stale = api.call(operator, "admin-product-migrate", "POST", "/v1/admin/products/" + id + "/schema-migration", Map.of("revision", 99, "attributes", Map.of("grade", "a")));
    assertThat(stale.status()).isEqualTo(409);

    var migrated = api.call(operator, "admin-product-migrate", "POST", "/v1/admin/products/" + id + "/schema-migration", Map.of("revision", unchanged.get("revision").asLong(), "attributes", Map.of("grade", "a"))).expect(200).body();
    assertThat(migrated.get("schemaVersion").asInt()).isEqualTo(2);
    assertThat(migrated.get("revision").asLong()).isEqualTo(unchanged.get("revision").asLong() + 1);

    // The storefront follows through the event: the new value is shown and filterable.
    dispatcher.dispatchAll();
    var page = api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products?f.grade=a", null).expect(200).body();
    assertThat(page.get("items").toString()).contains("evolving-one");
  }

  @Test
  void mediaAndCategoryMetadataAreValidatedAndPublic() {
    type("pictured", "PHYSICAL");
    String id = product("pictured", "pictured-one").get("id").asText();
    assertThat(api.call(operator, "admin-product-media", "PUT", "/v1/admin/products/" + id + "/media", Map.of("media", List.of(Map.of("url", "http://insecure.example/a.jpg")))).status()).isEqualTo(422);
    assertThat(api.call(operator, "admin-product-media", "PUT", "/v1/admin/products/" + UUID.randomUUID() + "/media", Map.of("media", List.of())).status()).isEqualTo(404);
    api.call(operator, "admin-product-media", "PUT", "/v1/admin/products/" + id + "/media", Map.of("media", List.of(Map.of("url", "https://cdn.example/a.jpg", "alt", Map.of("en", "Front"))))).expect(200);
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + id + "/publish", null).expect(200);
    dispatcher.dispatchAll();
    var detail = api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/pictured-one", null).expect(200).body();
    assertThat(detail.get("media").get(0).get("url").asText()).isEqualTo("https://cdn.example/a.jpg");
    assertThat(detail.get("media").get(0).get("kind").asText()).isEqualTo("IMAGE");

    String category = api.call(operator, "admin-category-create", "POST", "/v1/admin/categories", Map.of("slug", "seo-one", "name", Map.of("en", "Seo"))).expect(201).body().get("id").asText();
    assertThat(api.call(operator, "admin-category-seo", "PUT", "/v1/admin/categories/" + category + "/seo", Map.of("title", Map.of("en", "x".repeat(71)))).status()).isEqualTo(422);
    api.call(operator, "admin-category-seo", "PUT", "/v1/admin/categories/" + category + "/seo", Map.of("title", Map.of("en", "Best stones"), "description", Map.of("en", "All of them"))).expect(200);
    var tree = api.call(Caller.anonymous(), "store-categories", "GET", "/v1/store/categories", null).expect(200).body();
    assertThat(tree.toString()).contains("Best stones");
  }

  @Test
  void searchCardsCarryTheLowestActivePriceAndTheFirstImage() {
    type("carded", "PHYSICAL");
    String id = product("carded", "carded-one").get("id").asText();
    String bare = product("carded", "carded-bare").get("id").asText();
    api.call(operator, "admin-product-media", "PUT", "/v1/admin/products/" + id + "/media", Map.of("media", List.of(
        Map.of("url", "https://cdn.example/card.jpg", "alt", Map.of("en", "Card"))))).expect(200);
    for (String productId : List.of(id, bare)) api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + productId + "/publish", null).expect(200);
    dispatcher.dispatchAll();
    String variant = offer("carded-one");
    api.call(operator, "admin-offer-create", "POST", "/v1/admin/offers", Map.of("variantId", variant, "amount", 5000, "currency", "IRR", "quantity", 3)).expect(201);
    dispatcher.dispatchAll();

    var items = api.call(Caller.anonymous(), "store-products-search", "GET", "/v1/store/products?q=carded", null).expect(200).body().get("items");
    var cards = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
    items.forEach(card -> cards.put(card.get("slug").asText(), card));
    assertThat(cards.get("carded-one").get("fromPrice").get("amount").decimalValue()).isEqualByComparingTo("5000");
    assertThat(cards.get("carded-one").get("fromPrice").get("currency").asText()).isEqualTo("IRR");
    assertThat(cards.get("carded-one").get("image").get("url").asText()).isEqualTo("https://cdn.example/card.jpg");
    // A product without an offer or an image says so, rather than inventing either.
    assertThat(cards.get("carded-bare").get("fromPrice").isNull()).isTrue();
    assertThat(cards.get("carded-bare").get("image").isNull()).isTrue();
  }

  private String sellingProduct(String slug) {
    var product = product("reviewable", slug);
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + product.get("id").asText() + "/publish", null).expect(200);
    return product.get("id").asText();
  }

  private String offer(String slug) {
    return api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/" + slug, null).expect(200).body().get("product").get("variants").get(0).get("variantId").asText();
  }

  @Test
  void onlyABuyerReviewsOnceAndModerationHidesWithoutDeleting() {
    type("reviewable", "PHYSICAL");
    String productId = sellingProduct("review-me");
    dispatcher.dispatchAll();
    String offerId = api.call(operator, "admin-offer-create", "POST", "/v1/admin/offers", Map.of("variantId", offer("review-me"), "amount", 1000, "currency", "IRR", "quantity", 5)).expect(201).body().get("offerId").asText();
    dispatcher.dispatchAll();
    var buyer = Caller.customer();
    var stranger = Caller.customer();

    var refused = api.call(stranger, "shop-review-write", "POST", "/v1/shop/products/" + productId + "/reviews", Map.of("rating", 5, "body", "Never bought it"));
    assertThat(refused.code()).isEqualTo("REVIEW_NEEDS_PURCHASE");
    assertThat(api.call(Caller.anonymous(), "shop-review-write", "POST", "/v1/shop/products/" + productId + "/reviews", Map.of("rating", 5, "body", "x")).status()).isEqualTo(401);

    api.call(buyer, "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offerId, "quantity", 1)).expect(200);
    var checkout = api.call(buyer, "shop-checkout", "POST", "/v1/shop/checkouts", Map.of("idempotencyKey", "review-" + UUID.randomUUID())).expect(201).body();
    assertThat(api.call(buyer, "shop-review-write", "POST", "/v1/shop/products/" + productId + "/reviews", Map.of("rating", 5, "body", "Unpaid")).code()).as("an unpaid order earns no review").isEqualTo("REVIEW_NEEDS_PURCHASE");
    String url = checkout.get("redirectUrl").asText();
    hooks.post().uri("/hooks/fake-psp/sessions/" + url.substring(url.lastIndexOf('/') + 1) + "/approve").retrieve().toBodilessEntity();
    api.call(buyer, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    dispatcher.dispatchAll();

    assertThat(api.call(buyer, "shop-review-write", "POST", "/v1/shop/products/" + productId + "/reviews", Map.of("rating", 9, "body", "x")).status()).isEqualTo(422);
    var review = api.call(buyer, "shop-review-write", "POST", "/v1/shop/products/" + productId + "/reviews", Map.of("rating", 4, "body", "Good stone")).expect(201).body();
    assertThat(api.call(buyer, "shop-review-write", "POST", "/v1/shop/products/" + productId + "/reviews", Map.of("rating", 1, "body", "Again")).code()).isEqualTo("REVIEW_EXISTS");

    var pub = api.call(Caller.anonymous(), "store-product-reviews", "GET", "/v1/store/products/" + productId + "/reviews", null).expect(200).body();
    assertThat(pub.get("count").asInt()).isEqualTo(1);
    assertThat(pub.get("averageRating").decimalValue()).isEqualByComparingTo("4.0");
    assertThat(pub.toString()).doesNotContain(buyer.hiveUserId().toString());

    api.call(operator, "admin-review-hide", "POST", "/v1/admin/reviews/" + review.get("reviewId").asText() + "/hide", null).expect(204);
    assertThat(api.call(Caller.anonymous(), "store-product-reviews", "GET", "/v1/store/products/" + productId + "/reviews", null).body().get("count").asInt()).isZero();
    assertThat(api.call(operator, "admin-reviews", "GET", "/v1/admin/reviews", null).expect(200).body().toString()).contains("HIDDEN", "Good stone");

    // The operator sees who ordered and the payment, and nothing the provider would hold.
    assertThat(api.call(operator, "admin-customers", "GET", "/v1/admin/customers", null).expect(200).body().toString()).contains(buyer.hiveUserId().toString());
    var payments = api.call(operator, "admin-payments", "GET", "/v1/admin/payments", null).expect(200).body();
    assertThat(payments.toString()).contains(checkout.get("paymentId").asText(), "CAPTURED");
    assertThat(api.call(operator, "admin-store-capabilities", "GET", "/v1/admin/store/capabilities", null).expect(200).body().get("reviews").asBoolean()).isTrue();
  }

  @Test
  void aSellerListsItsOwnAssetsAndNobodyElsesAndSeesWhatItCanActOn() {
    type("selling", "DIGITAL");
    var owner = Caller.customer();
    var other = Caller.customer();
    String seller = api.call(owner, "seller-apply", "POST", "/v1/seller/applications", Map.of("displayName", Map.of("en", "Own Shop"))).expect(201).body().get("sellerId").asText();
    api.call(operator, "admin-seller-verify", "POST", "/v1/admin/sellers/" + seller + "/verify", null).expect(200);
    api.call(operator, "admin-seller-approve", "POST", "/v1/admin/sellers/" + seller + "/approve", null).expect(200);
    var listing = api.call(owner, "seller-submission", "POST", "/v1/seller/sellers/" + seller + "/submissions", Map.of("productType", "selling", "title", Map.of("en", "Own"), "slug", "own-prompt",
        "attributes", Map.of("note", "x"), "variants", List.of(Map.of("sku", "OWN-1", "attributes", Map.of())))).expect(201).body();

    var mine = api.call(owner, "seller-listings", "GET", "/v1/seller/sellers/" + seller + "/listings", null).expect(200).body().get(0);
    assertThat(mine.get("slug").asText()).isEqualTo("own-prompt");
    assertThat(mine.get("variantIds").size()).isEqualTo(1);

    String asset = api.call(owner, "seller-asset-create", "POST", "/v1/seller/sellers/" + seller + "/assets", Map.of("productId", listing.get("productId").asText(), "kind", "TEXT", "title", "Own asset")).expect(201).body().get("assetId").asText();
    api.call(owner, "seller-asset-text", "PUT", "/v1/seller/assets/" + asset + "/text", Map.of("content", "SECRET ONE")).expect(200);
    api.call(owner, "seller-asset-text", "PUT", "/v1/seller/assets/" + asset + "/text", Map.of("content", "SECRET TWO")).expect(200);

    var assets = api.call(owner, "seller-assets", "GET", "/v1/seller/sellers/" + seller + "/assets", null).expect(200).body();
    assertThat(assets.size()).isEqualTo(1);
    assertThat(assets.get(0).get("versions").size()).as("each save is a new version").isEqualTo(2);
    assertThat(assets.toString()).doesNotContain("SECRET");
    assertThat(api.call(other, "seller-assets", "GET", "/v1/seller/sellers/" + seller + "/assets", null).status()).isEqualTo(404);
  }
}
