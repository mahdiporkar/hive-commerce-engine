package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.platform.events.OutboxDispatcher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Scenario E: a single-vendor store upgrades to multi-vendor; all products and orders are kept. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModeUpgradeIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.store.key", () -> "upgrade-store");
    registry.add("commerce.store.mode", () -> "SINGLE_VENDOR");
    registry.add("commerce.capabilities.seller-onboarding", () -> "true");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
    registry.add("commerce.inventory.expiry-interval-ms", () -> "3600000");
    registry.add("commerce.payments.sweep-interval-ms", () -> "3600000");
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired OutboxDispatcher dispatcher;

  @Test
  void scenarioE_upgradeKeepsProductsAndOrders() {
    var api = new ApiClient(port, json);
    var hooks = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (req, res) -> {}).build();
    var operator = Caller.operator();

    // Single vendor: an order for the store's internal seller; seller onboarding is closed.
    api.call(operator, "admin-product-type-create", "POST", "/v1/admin/product-types", Map.of("key", "item", "name", Map.of("en", "Item"), "attributes", List.of())).expect(201);
    api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/item/versions/1/publish", null).expect(200);
    var product = api.call(operator, "admin-product-create", "POST", "/v1/admin/products", Map.of("productType", "item", "title", Map.of("en", "Item"), "slug", "item-1",
        "attributes", Map.of(), "variants", List.of(Map.of("sku", "ITEM-1", "attributes", Map.of())))).expect(201).body();
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + product.get("id").asText() + "/publish", null).expect(200);
    String offer = api.call(operator, "admin-offer-create", "POST", "/v1/admin/offers", Map.of("variantId", product.get("variants").get(0).get("id").asText(),
        "amount", "1000000", "currency", "IRR", "quantity", 5)).expect(201).body().get("offerId").asText();
    dispatcher.dispatchAll();
    var buyer = Caller.operator();
    api.call(buyer, "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offer, "quantity", 1)).expect(200);
    var checkout = api.call(buyer, "shop-checkout", "POST", "/v1/shop/checkouts", Map.of("idempotencyKey", "upgrade-order-1")).expect(201).body();
    String url = checkout.get("redirectUrl").asText();
    hooks.post().uri("/hooks/fake-psp/sessions/" + url.substring(url.lastIndexOf('/') + 1) + "/approve").retrieve().toBodilessEntity();
    api.call(buyer, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    dispatcher.dispatchAll();
    String orderId = checkout.get("orderId").asText();
    assertThat(api.call(buyer, "shop-order", "GET", "/v1/shop/orders/" + orderId, null).body().get("status").asText()).isEqualTo("PAID");
    var seller = Caller.operator();
    var closed = api.call(seller, "seller-apply", "POST", "/v1/seller/applications", Map.of("displayName", Map.of("en", "Too early")));
    assertThat(closed.status()).isEqualTo(409);
    assertThat(closed.code()).isEqualTo("MARKETPLACE_DISABLED");

    // Upgrade.
    assertThat(api.call(operator, "admin-store-mode", "POST", "/v1/admin/store/mode", Map.of("mode", "MULTI_VENDOR")).expect(200).body().get("mode").asText()).isEqualTo("MULTI_VENDOR");
    assertThat(api.call(operator, "admin-store", "GET", "/v1/admin/store", null).expect(200).body().get("mode").asText()).isEqualTo("MULTI_VENDOR");

    // Everything from before is intact and still sells.
    assertThat(api.call(buyer, "shop-order", "GET", "/v1/shop/orders/" + orderId, null).expect(200).body().get("status").asText()).isEqualTo("PAID");
    var detail = api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/item-1", null).expect(200).body();
    assertThat(detail.get("offers").get(0).get("offerId").asText()).isEqualTo(offer);
    api.call(Caller.operator(), "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offer, "quantity", 1)).expect(200);

    // Now sellers may join, and a second seller can offer the same product next to the store.
    String sellerId = api.call(seller, "seller-apply", "POST", "/v1/seller/applications", Map.of("displayName", Map.of("en", "New seller"))).expect(201).body().get("sellerId").asText();
    api.call(operator, "admin-seller-verify", "POST", "/v1/admin/sellers/" + sellerId + "/verify", null).expect(200);
    api.call(operator, "admin-seller-approve", "POST", "/v1/admin/sellers/" + sellerId + "/approve", null).expect(200);
    api.call(seller, "seller-offer-create", "POST", "/v1/seller/sellers/" + sellerId + "/offers", Map.of("variantId", product.get("variants").get(0).get("id").asText(),
        "amount", "900000", "currency", "IRR", "quantity", 2)).expect(201);
    dispatcher.dispatchAll();
    assertThat(api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/item-1", null).body().get("offers")).hasSize(2);

    // Downgrading is refused while external sellers are active.
    var refused = api.call(operator, "admin-store-mode", "POST", "/v1/admin/store/mode", Map.of("mode", "SINGLE_VENDOR"));
    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.code()).isEqualTo("MODE_DOWNGRADE_BLOCKED");
  }
}
