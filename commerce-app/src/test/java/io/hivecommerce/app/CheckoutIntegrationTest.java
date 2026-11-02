package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.inventory.application.InventoryService;
import io.hivecommerce.platform.events.OutboxDispatcher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Phase 2 transaction flow at engine level: carts, the checkout saga, reservations, verified payments (webhook inbox
 * and customer verification), fulfillment, refunds and compensation. Scenario A runs here and through Hive (tests/e2e).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.store.key", () -> "checkout-store");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
    registry.add("commerce.inventory.expiry-interval-ms", () -> "3600000");
    registry.add("commerce.payments.sweep-interval-ms", () -> "3600000");
    registry.add("commerce.pricing.tax-rate-percent", () -> "9");
    registry.add("commerce.pricing.flat-shipping", () -> "500000");
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired OutboxDispatcher dispatcher;
  @Autowired InventoryService inventory;
  ApiClient api;
  RestClient hooks;
  Caller operator;
  String gemOffer, bowlOffer, bowlOfferId;

  @BeforeAll
  void createCatalogAndOffers() throws Exception {
    api = new ApiClient(port, json);
    hooks = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (req, res) -> {}).build();
    operator = Caller.operator();
    var ids = new io.hivecommerce.app.support.CatalogFixtureLoader(api, json).load(Path.of("..", "fixtures", "handicraft", "catalog.json"), operator);
    gemOffer = createOffer(ids.get("product:lalejin-turquoise-bowl"), "850000000", 1, true);
    bowlOffer = createOffer(ids.get("product:walnut-carved-box"), "1250000", 10, false);
    dispatcher.dispatchAll(); // OfferCreated → stock items
  }

  private String createOffer(String productId, String price, int quantity, boolean unique) {
    var product = api.call(operator, "admin-product-get", "GET", "/v1/admin/products/" + productId, null).expect(200).body();
    String variantId = product.get("variants").get(0).get("id").asText();
    return api.call(operator, "admin-offer-create", "POST", "/v1/admin/offers",
        Map.of("variantId", variantId, "amount", price, "currency", "IRR", "quantity", quantity, "uniqueItem", unique)).expect(201).body().get("offerId").asText();
  }

  private ApiClient.Response addToCart(Caller shopper, String offerId, int quantity) {
    return api.call(shopper, "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offerId, "quantity", quantity));
  }

  private ApiClient.Response checkout(Caller shopper, String key) {
    return api.call(shopper, "shop-checkout", "POST", "/v1/shop/checkouts", Map.of("idempotencyKey", key));
  }

  private static String ref(JsonNode checkout) {
    String url = checkout.get("redirectUrl").asText();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  private int psp(String ref, String decision) {
    return hooks.post().uri("/hooks/fake-psp/sessions/" + ref + "/" + decision).retrieve().toBodilessEntity().getStatusCode().value();
  }

  private ApiClient.Response webhook(String body, String signature, Map<String, String> extraHeaders) {
    var request = hooks.post().uri("/hooks/payments/fake").contentType(MediaType.APPLICATION_JSON).header("X-Fake-Signature", signature);
    for (var h : extraHeaders.entrySet()) request = request.header(h.getKey(), h.getValue());
    return request.body(body).exchange((req, res) -> new ApiClient.Response(res.getStatusCode().value(), json.readTree(res.getBody().readAllBytes())));
  }

  private static String sign(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(EngineTestSupport.FAKE_PSP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private JsonNode order(Caller shopper, String orderId) {
    return api.call(shopper, "shop-order", "GET", "/v1/shop/orders/" + orderId, null).expect(200).body();
  }

  private String key() { return "checkout-" + UUID.randomUUID(); }

  @Test
  void scenarioA_twoConcurrentBuyersOfAUniqueGemProduceExactlyOneOrder() throws Exception {
    var buyers = List.of(Caller.operator(), Caller.operator());
    for (var buyer : buyers) addToCart(buyer, gemOffer, 1).expect(200);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    var results = new ArrayList<Future<ApiClient.Response>>();
    for (var buyer : buyers) results.add(pool.submit(() -> {
      start.await();
      return checkout(buyer, key());
    }));
    start.countDown();
    var responses = new ArrayList<ApiClient.Response>();
    for (var f : results) responses.add(f.get());
    pool.shutdown();
    assertThat(responses).extracting(ApiClient.Response::status).containsExactlyInAnyOrder(201, 409);
    var won = responses.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
    var lost = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
    assertThat(lost.code()).isEqualTo("OUT_OF_STOCK");
    Caller winner = buyers.get(responses.indexOf(won));
    String orderId = won.body().get("orderId").asText();

    // The shopper pays at the PSP and returns: the redirect alone proves nothing.
    assertThat(psp(ref(won.body()), "approve")).isEqualTo(200);
    dispatcher.dispatchAll();
    assertThat(order(winner, orderId).get("status").asText()).isEqualTo("PLACED");

    // Signed webhook from the PSP confirms; a replay changes nothing.
    String event = "{\"eventId\":\"evt-" + UUID.randomUUID() + "\",\"ref\":\"" + ref(won.body()) + "\",\"outcome\":\"PAID\"}";
    assertThat(webhook(event, sign(event), Map.of()).body().get("status").asText()).isEqualTo("processed");
    assertThat(webhook(event, sign(event), Map.of()).body().get("status").asText()).isEqualTo("duplicate");
    dispatcher.dispatchAll();
    var paid = order(winner, orderId);
    assertThat(paid.get("status").asText()).isEqualTo("PAID");
    assertThat(paid.get("total").decimalValue()).isEqualByComparingTo("927000000"); // 850,000,000 + 9 % tax + 500,000 shipping
    // Customer verification after the webhook is a harmless no-op.
    var verified = api.call(winner, "shop-payment-verify", "POST", "/v1/shop/payments/" + won.body().get("paymentId").asText() + "/verify", null).expect(200);
    assertThat(verified.body().get("status").asText()).isEqualTo("CAPTURED");

    // Fulfillment is recorded and completes the order.
    var fulfillments = api.call(operator, "admin-order-fulfillments", "GET", "/v1/admin/orders/" + orderId + "/fulfillments", null).expect(200).body();
    String fulfillmentId = fulfillments.get(0).get("fulfillmentId").asText();
    api.call(operator, "admin-fulfillment-ship", "POST", "/v1/admin/fulfillments/" + fulfillmentId + "/ship", Map.of("carrier", "Iran Post", "trackingCode", "IR1")).expect(200);
    api.call(operator, "admin-fulfillment-deliver", "POST", "/v1/admin/fulfillments/" + fulfillmentId + "/deliver", null).expect(200);
    dispatcher.dispatchAll();
    assertThat(order(winner, orderId).get("status").asText()).isEqualTo("COMPLETED");

    // The unique gem is gone for good: the other buyer cannot retry successfully.
    Caller loser = buyers.get(responses.indexOf(lost));
    assertThat(checkout(loser, key()).code()).isEqualTo("OUT_OF_STOCK");
    var detail = api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/lalejin-turquoise-bowl", null).expect(200).body();
    assertThat(detail.get("offers").get(0).get("available").asInt()).isZero();
  }

  @Test
  void customerVerificationConfirmsWithoutWebhookAndCheckoutIsIdempotent() {
    var shopper = Caller.operator();
    addToCart(shopper, bowlOffer, 1).expect(200);
    String key = key();
    var first = checkout(shopper, key).expect(201).body();
    var again = checkout(shopper, key).expect(201).body();
    assertThat(again.get("checkoutId")).isEqualTo(first.get("checkoutId"));
    assertThat(again.get("orderId")).isEqualTo(first.get("orderId"));
    psp(ref(first), "approve");
    var verified = api.call(shopper, "shop-payment-verify", "POST", "/v1/shop/payments/" + first.get("paymentId").asText() + "/verify", null).expect(200);
    assertThat(verified.body().get("status").asText()).isEqualTo("CAPTURED");
    dispatcher.dispatchAll();
    assertThat(order(shopper, first.get("orderId").asText()).get("status").asText()).isEqualTo("PAID");
    assertThat(api.call(shopper, "shop-checkout-get", "GET", "/v1/shop/checkouts/" + first.get("checkoutId").asText(), null).body().get("state").asText())
        .isEqualTo("COMPLETED");
  }

  @Test
  void declinedPaymentCancelsTheOrderAndReleasesStock() {
    var shopper = Caller.operator();
    addToCart(shopper, bowlOffer, 2).expect(200);
    int before = available(bowlOffer);
    var checkout = checkout(shopper, key()).expect(201).body();
    assertThat(available(bowlOffer)).isEqualTo(before - 2);
    psp(ref(checkout), "decline");
    api.call(shopper, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    dispatcher.dispatchAll();
    assertThat(order(shopper, checkout.get("orderId").asText()).get("status").asText()).isEqualTo("CANCELLED");
    assertThat(available(bowlOffer)).isEqualTo(before);
  }

  @Test
  void captureAfterReservationExpiryIsRefundedAutomatically() throws Exception {
    var shopper = Caller.operator();
    addToCart(shopper, bowlOffer, 1).expect(200);
    var checkout = checkout(shopper, key()).expect(201).body();
    try (var c = EngineTestSupport.ownerConnection(); var s = c.createStatement()) {
      s.executeUpdate("update inventory.reservation set expires_at = now() - interval '1 second' where checkout_attempt_id = '" + checkout.get("checkoutId").asText() + "'");
    }
    inventory.expireReservations();
    dispatcher.dispatchAll();
    String orderId = checkout.get("orderId").asText();
    assertThat(order(shopper, orderId).get("status").asText()).isEqualTo("CANCELLED");
    psp(ref(checkout), "approve");
    api.call(shopper, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    dispatcher.dispatchAll();
    assertThat(order(shopper, orderId).get("status").asText()).isEqualTo("PAID_UNFULFILLABLE");
    try (var c = EngineTestSupport.ownerConnection(); var rs = c.createStatement().executeQuery(
        "select count(*) from payment.refund where status = 'COMPLETED' and payment_id = '" + checkout.get("paymentId").asText() + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void refundsAreIdempotentAndNeverExceedTheCapture() {
    var shopper = Caller.operator();
    addToCart(shopper, bowlOffer, 1).expect(200);
    var checkout = checkout(shopper, key()).expect(201).body();
    psp(ref(checkout), "approve");
    api.call(shopper, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    String orderId = checkout.get("orderId").asText();
    var total = checkout.get("total").decimalValue();
    var half = total.divide(java.math.BigDecimal.TWO).setScale(0, java.math.RoundingMode.DOWN);
    var r1 = api.call(operator, "admin-order-refund", "POST", "/v1/admin/orders/" + orderId + "/refunds", Map.of("idempotencyKey", "refund-1", "amount", half)).expect(200);
    var r1again = api.call(operator, "admin-order-refund", "POST", "/v1/admin/orders/" + orderId + "/refunds", Map.of("idempotencyKey", "refund-1", "amount", half)).expect(200);
    assertThat(r1again.body().get("refundId")).isEqualTo(r1.body().get("refundId"));
    var tooMuch = api.call(operator, "admin-order-refund", "POST", "/v1/admin/orders/" + orderId + "/refunds", Map.of("idempotencyKey", "refund-2", "amount", total));
    assertThat(tooMuch.status()).isEqualTo(409);
    assertThat(tooMuch.code()).isEqualTo("REFUND_EXCEEDS_CAPTURE");
  }

  @Test
  void customersSeeOnlyTheirOwnOrdersAndPayments() {
    var alice = Caller.operator();
    addToCart(alice, bowlOffer, 1).expect(200);
    var checkout = checkout(alice, key()).expect(201).body();
    var mallory = Caller.operator();
    assertThat(api.call(mallory, "shop-order", "GET", "/v1/shop/orders/" + checkout.get("orderId").asText(), null).status()).isEqualTo(404);
    assertThat(api.call(mallory, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).status()).isEqualTo(404);
    assertThat(api.call(mallory, "shop-checkout-get", "GET", "/v1/shop/checkouts/" + checkout.get("checkoutId").asText(), null).status()).isEqualTo(404);
    assertThat(api.call(mallory, "shop-orders", "GET", "/v1/shop/orders", null).expect(200).body()).isEmpty();
    assertThat(api.call(alice, "shop-orders", "GET", "/v1/shop/orders", null).expect(200).body()).hasSize(1);
  }

  @Test
  void webhooksRequireAValidSignatureAndRefuseSessions() throws Exception {
    String event = "{\"eventId\":\"evt-x\",\"ref\":\"fake_unknown\",\"outcome\":\"PAID\"}";
    assertThat(webhook(event, "deadbeef", Map.of()).status()).isEqualTo(401);
    // Rejected before any processing: by the resource server (401) or by the ingress guard (403).
    assertThat(webhook(event, sign(event), Map.of("Authorization", "Bearer x")).status()).isIn(401, 403);
    assertThat(webhook(event, sign(event), Map.of("Cookie", "HIVE_SESSION=x")).status()).isEqualTo(403);
  }

  @Test
  void guestCartIsMergedAtSignIn() {
    var created = api.call(Caller.anonymous(), "shop-cart-create", "POST", "/v1/shop/carts", null).expect(201).body();
    String token = created.get("cartToken").asText();
    assertThat(token).hasSizeGreaterThan(30);
    api.call(Caller.anonymous(), "shop-cart-add", "POST", "/v1/shop/carts/current/items?cart=" + token, Map.of("offerId", bowlOffer, "quantity", 2)).expect(200);
    var guestCart = api.call(Caller.anonymous(), "shop-cart", "GET", "/v1/shop/carts/current?cart=" + token, null).expect(200).body();
    assertThat(guestCart.get("totals").get("currency").asText()).isEqualTo("IRR");
    assertThat(api.call(Caller.anonymous(), "shop-cart", "GET", "/v1/shop/carts/current?cart=wrong-token", null).status()).isEqualTo(404);
    var shopper = Caller.operator();
    var merged = api.call(shopper, "shop-cart", "GET", "/v1/shop/carts/current?cart=" + token, null).expect(200).body();
    assertThat(merged.get("lines")).hasSize(1);
    assertThat(merged.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(api.call(Caller.anonymous(), "shop-cart", "GET", "/v1/shop/carts/current?cart=" + token, null).status()).isEqualTo(404);
    assertThat(api.call(Caller.anonymous(), "shop-checkout", "POST", "/v1/shop/checkouts", Map.of("idempotencyKey", key())).status()).isEqualTo(401);
  }

  /** ADR-0004: every HYBRID operation that is not explicitly anonymous-capable answers 401 to anonymous callers. */
  @Test
  void customerOnlyHybridOperationsRejectAnonymousCallers() throws Exception {
    var anonymousCapable = java.util.Set.of("shop-cart-create", "shop-cart", "shop-cart-add", "shop-cart-remove");
    JsonNode template = json.readTree(Path.of("..", "hive", "templates", "commerce-service.json").toFile());
    int checked = 0;
    for (JsonNode op : template.get("operations")) {
      if (!"HYBRID".equals(op.get("access").asText()) || anonymousCapable.contains(op.get("key").asText())) continue;
      String path = op.get("pathPattern").asText().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
      var response = api.call(Caller.anonymous(), op.get("key").asText(), op.get("method").asText(), path,
          "GET".equals(op.get("method").asText()) ? null : Map.of("idempotencyKey", key()));
      assertThat(response.status()).as(op.get("key").asText()).isEqualTo(401);
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(5);
  }

  private int available(String offerId) {
    try (var c = EngineTestSupport.ownerConnection(); var rs = c.createStatement().executeQuery(
        "select on_hand - reserved from inventory.stock_item where offer_id = '" + offerId + "'")) {
      rs.next();
      return rs.getInt(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
