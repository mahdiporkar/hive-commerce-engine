package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.platform.events.OutboxDispatcher;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * Phase 3 digital commerce against real PostgreSQL and real S3-compatible storage: Scenario B (a store sells a PDF; the
 * buyer gets a signed download; a non-buyer and an expired URL both fail), upload verification, licenses, revocation by
 * refund, prompt text delivered inline, public previews, mixed physical + digital orders.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DigitalDeliveryIntegrationTest {
  private static final byte[] PDF = ("%PDF-1.7\n% test guide\n" + "x".repeat(2000) + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.store.key", () -> "pdf-store");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
    registry.add("commerce.digital.download-ttl", () -> "PT2S");
    registry.add("commerce.inventory.expiry-interval-ms", () -> "3600000");
    registry.add("commerce.payments.sweep-interval-ms", () -> "3600000");
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired OutboxDispatcher dispatcher;
  final HttpClient http = HttpClient.newHttpClient();
  ApiClient api;
  RestClient hooks;
  Caller operator;
  String pdfOffer, pdfAsset, pdfProduct;

  @BeforeAll
  void createPdfProduct() throws Exception {
    api = new ApiClient(port, json);
    hooks = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (req, res) -> {}).build();
    operator = Caller.operator();
    productType("ebook", "DIGITAL");
    var created = product("ebook", "guide");
    pdfProduct = created[0];
    pdfAsset = asset(pdfProduct, "FILE", "The Guide", 3, false);
    assertThat(upload(pdfAsset, PDF, "application/pdf", sha256(PDF)).status()).isEqualTo(200);
    pdfOffer = offer(created[1], "1200000", null);
    publish(pdfProduct);
    dispatcher.dispatchAll();
  }

  private void productType(String key, String kind) {
    api.call(operator, "admin-product-type-create", "POST", "/v1/admin/product-types", Map.of("key", key, "name", Map.of("en", key), "kind", kind, "attributes", List.of())).expect(201);
    api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/" + key + "/versions/1/publish", null).expect(200);
  }

  /** @return product id and variant id */
  private String[] product(String type, String slug) {
    String unique = slug + "-" + UUID.randomUUID().toString().substring(0, 8);
    var p = api.call(operator, "admin-product-create", "POST", "/v1/admin/products", Map.of("productType", type, "title", Map.of("en", slug, "fa", "کتاب"), "slug", unique,
        "attributes", Map.of(), "variants", List.of(Map.of("sku", unique.toUpperCase(), "attributes", Map.of())))).expect(201).body();
    return new String[] {p.get("id").asText(), p.get("variants").get(0).get("id").asText()};
  }

  private String asset(String productId, String kind, String title, Integer limit, boolean updates) {
    var body = new java.util.HashMap<String, Object>(Map.of("productId", productId, "kind", kind, "title", title, "updatesIncluded", updates));
    if (limit != null) body.put("downloadLimit", limit);
    return api.call(operator, "admin-asset-create", "POST", "/v1/admin/assets", body).expect(201).body().get("assetId").asText();
  }

  private String offer(String variantId, String price, Integer quantity) {
    var body = new java.util.HashMap<String, Object>(Map.of("variantId", variantId, "amount", price, "currency", "IRR"));
    if (quantity != null) body.put("quantity", quantity);
    return api.call(operator, "admin-offer-create", "POST", "/v1/admin/offers", body).expect(201).body().get("offerId").asText();
  }

  private void publish(String productId) {
    api.call(operator, "admin-product-publish", "POST", "/v1/admin/products/" + productId + "/publish", null).expect(200);
  }

  /** Requests a signed form, posts the file directly to storage (like a browser), then finalizes. */
  private ApiClient.Response upload(String assetId, byte[] content, String contentType, String declaredSha) throws Exception {
    var form = api.call(operator, "admin-asset-upload", "POST", "/v1/admin/assets/" + assetId + "/uploads",
        Map.of("contentType", contentType, "size", content.length, "sha256", declaredSha)).expect(201).body();
    assertThat(postToStorage(form, content).statusCode()).isBetween(200, 299);
    return api.call(operator, "admin-asset-finalize", "POST", "/v1/admin/assets/" + assetId + "/uploads/" + form.get("uploadId").asText() + "/finalize", null);
  }

  private HttpResponse<String> postToStorage(JsonNode form, byte[] content) throws Exception {
    String boundary = "----test" + UUID.randomUUID();
    var body = new ByteArrayOutputStream();
    var names = form.get("fields").fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + form.get("fields").get(name).asText() + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    body.write(content);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return http.send(HttpRequest.newBuilder(URI.create(form.get("url").asText())).header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  /** Cart → checkout → provider approval → verification → events delivered. Returns the checkout view. */
  private JsonNode buy(Caller shopper, String... offerIds) {
    for (String offerId : offerIds) {
      api.call(shopper, "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offerId, "quantity", 1)).expect(200);
    }
    var checkout = api.call(shopper, "shop-checkout", "POST", "/v1/shop/checkouts", Map.of("idempotencyKey", "buy-" + UUID.randomUUID())).expect(201).body();
    String url = checkout.get("redirectUrl").asText();
    hooks.post().uri("/hooks/fake-psp/sessions/" + url.substring(url.lastIndexOf('/') + 1) + "/approve").retrieve().toBodilessEntity();
    api.call(shopper, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    dispatcher.dispatchAll();
    return checkout;
  }

  private JsonNode library(Caller shopper) {
    return api.call(shopper, "shop-library", "GET", "/v1/shop/library", null).expect(200).body();
  }

  private ApiClient.Response download(Caller shopper, String entitlementId) {
    return api.call(shopper, "shop-download", "POST", "/v1/shop/library/" + entitlementId + "/downloads", null);
  }

  private HttpResponse<byte[]> fetch(String url) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void scenarioB_buyerDownloadsViaSignedUrl_nonBuyerAndExpiredUrlFail() throws Exception {
    var buyer = Caller.operator();
    var checkout = buy(buyer, pdfOffer);
    assertThat(checkout.get("total").decimalValue()).isEqualByComparingTo("1200000"); // no shipping for digital lines

    // Digital-only order completes once entitlements are granted.
    var order = api.call(buyer, "shop-order", "GET", "/v1/shop/orders/" + checkout.get("orderId").asText(), null).expect(200).body();
    assertThat(order.get("status").asText()).isEqualTo("COMPLETED");

    var items = library(buyer);
    assertThat(items).hasSize(1);
    String entitlement = items.get(0).get("entitlementId").asText();

    var granted = download(buyer, entitlement).expect(200).body();
    assertThat(granted.has("expiresAt") && !granted.get("expiresAt").isNull()).isTrue();
    String url = granted.get("url").asText();
    assertThat(url).doesNotContain("Location");
    var file = fetch(url);
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(file.body()).isEqualTo(PDF);
    assertThat(file.headers().firstValue("content-disposition").orElse("")).contains("attachment");

    // A non-buyer cannot get a URL, even with the entitlement id.
    var stranger = Caller.operator();
    assertThat(library(stranger)).isEmpty();
    assertThat(download(stranger, entitlement).status()).isEqualTo(404);

    // The signed URL expires (TTL 2 s in this test), and the object is private without a signature.
    Thread.sleep(3000);
    assertThat(fetch(url).statusCode()).isEqualTo(403);
    assertThat(fetch(url.substring(0, url.indexOf('?'))).statusCode()).isEqualTo(403);

    // License limit: 3 downloads; the fourth is refused. Every grant is logged.
    download(buyer, entitlement).expect(200);
    download(buyer, entitlement).expect(200);
    var refused = download(buyer, entitlement);
    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.code()).isEqualTo("DOWNLOAD_LIMIT_REACHED");
    try (var c = EngineTestSupport.ownerConnection(); var rs = c.createStatement().executeQuery(
        "select count(*) from digital.download_access_log where entitlement_id = '" + entitlement + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(3);
    }
  }

  @Test
  void refundOfTheLineRevokesAccess() {
    var buyer = Caller.operator();
    var checkout = buy(buyer, pdfOffer);
    String entitlement = library(buyer).get(0).get("entitlementId").asText();
    download(buyer, entitlement).expect(200);
    var order = api.call(operator, "admin-order", "GET", "/v1/admin/orders/" + checkout.get("orderId").asText(), null).expect(200).body();
    String lineId = order.get("sellerOrders").get(0).get("lines").get(0).get("lineId").asText();
    api.call(operator, "admin-order-refund", "POST", "/v1/admin/orders/" + checkout.get("orderId").asText() + "/refunds",
        Map.of("idempotencyKey", "refund-" + UUID.randomUUID(), "amount", "1200000", "orderLineIds", List.of(lineId))).expect(200);
    dispatcher.dispatchAll();
    assertThat(library(buyer).get(0).get("status").asText()).isEqualTo("REVOKED");
    var denied = download(buyer, entitlement);
    assertThat(denied.status()).isEqualTo(409);
    assertThat(denied.code()).isEqualTo("ENTITLEMENT_REVOKED");
  }

  @Test
  void operatorCanRevokeDirectly() {
    var buyer = Caller.operator();
    buy(buyer, pdfOffer);
    String entitlement = library(buyer).get(0).get("entitlementId").asText();
    api.call(operator, "admin-entitlement-revoke", "POST", "/v1/admin/entitlements/" + entitlement + "/revoke", Map.of("reason", "Chargeback")).expect(200);
    assertThat(download(buyer, entitlement).code()).isEqualTo("ENTITLEMENT_REVOKED");
  }

  @Test
  void finalizeRejectsWrongChecksumDisguisedTypesAndMalwareAndStorageEnforcesTheSize() throws Exception {
    String assetId = asset(pdfProduct, "FILE", "Bad uploads", null, true);
    var wrongSum = upload(assetId, PDF, "application/pdf", "0".repeat(64));
    assertThat(wrongSum.status()).isEqualTo(409);
    assertThat(wrongSum.code()).isEqualTo("UPLOAD_CHECKSUM_MISMATCH");

    byte[] exe = new byte[] {'M', 'Z', 0, 0, 1, 2, 3};
    var disguised = upload(assetId, exe, "application/pdf", sha256(exe));
    assertThat(disguised.code()).isEqualTo("UPLOAD_TYPE_MISMATCH");

    byte[] eicar = ("%PDF-1.7 " + "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
    var infected = upload(assetId, eicar, "application/pdf", sha256(eicar));
    assertThat(infected.code()).isEqualTo("UPLOAD_MALWARE_DETECTED");

    var notAllowed = api.call(operator, "admin-asset-upload", "POST", "/v1/admin/assets/" + assetId + "/uploads",
        Map.of("contentType", "application/x-msdownload", "size", 10, "sha256", "0".repeat(64)));
    assertThat(notAllowed.status()).isEqualTo(422);

    // The signed form is bound to the declared size: storage itself refuses a larger file.
    var form = api.call(operator, "admin-asset-upload", "POST", "/v1/admin/assets/" + assetId + "/uploads",
        Map.of("contentType", "application/pdf", "size", 10, "sha256", "0".repeat(64))).expect(201).body();
    assertThat(postToStorage(form, PDF).statusCode()).isGreaterThanOrEqualTo(400);
    var missing = api.call(operator, "admin-asset-finalize", "POST", "/v1/admin/assets/" + assetId + "/uploads/" + form.get("uploadId").asText() + "/finalize", null);
    assertThat(missing.code()).isEqualTo("UPLOAD_MISSING");

    var view = api.call(operator, "admin-asset-text", "PUT", "/v1/admin/assets/" + assetId + "/text", Map.of("content", "not for file assets"));
    assertThat(view.code()).isEqualTo("ASSET_KIND_MISMATCH");
  }

  @Test
  void promptTextIsDeliveredInlineAfterPurchaseAndNeverInPublicApis() {
    productType("ai-prompt", "DIGITAL");
    var created = product("ai-prompt", "prompt");
    String secret = "SECRET PROMPT: تو یک دستیار حرفه‌ای هستی";
    String assetId = asset(created[0], "TEXT", "Prompt", null, true);
    api.call(operator, "admin-asset-text", "PUT", "/v1/admin/assets/" + assetId + "/text", Map.of("content", secret)).expect(200);
    api.call(operator, "admin-asset-preview", "PUT", "/v1/admin/assets/" + assetId + "/preview", Map.of("preview", "A helpful assistant prompt")).expect(200);
    String offerId = offer(created[1], "500000", null);
    publish(created[0]);
    dispatcher.dispatchAll();

    var previews = api.call(Caller.anonymous(), "store-product-previews", "GET", "/v1/store/products/" + created[0] + "/previews", null).expect(200).body();
    assertThat(previews.get(0).get("preview").asText()).isEqualTo("A helpful assistant prompt");
    assertThat(previews.toString()).doesNotContain("SECRET").doesNotContain("assets/").doesNotContain("da_");

    var buyer = Caller.operator();
    buy(buyer, offerId);
    var entitlement = library(buyer).get(0);
    var delivered = download(buyer, entitlement.get("entitlementId").asText()).expect(200).body();
    assertThat(delivered.get("content").asText()).isEqualTo(secret);
    assertThat(delivered.get("url").isNull()).isTrue();

    // A buyer with update rights receives the new version.
    String newer = "SECRET PROMPT v2";
    api.call(operator, "admin-asset-text", "PUT", "/v1/admin/assets/" + assetId + "/text", Map.of("content", newer)).expect(200);
    var updated = download(buyer, entitlement.get("entitlementId").asText()).expect(200).body();
    assertThat(updated.get("version").asInt()).isEqualTo(2);
    assertThat(updated.get("content").asText()).isEqualTo(newer);
  }

  @Test
  void mixedPhysicalAndDigitalOrderKeepsBothPaths() {
    productType("framed-print", "PHYSICAL");
    var physical = product("framed-print", "print");
    String physicalOffer = offer(physical[1], "3000000", 5);
    publish(physical[0]);
    dispatcher.dispatchAll();
    var buyer = Caller.operator();
    var checkout = buy(buyer, physicalOffer, pdfOffer);
    String orderId = checkout.get("orderId").asText();
    var order = api.call(buyer, "shop-order", "GET", "/v1/shop/orders/" + orderId, null).expect(200).body();
    assertThat(order.get("status").asText()).isEqualTo("PAID");
    assertThat(order.get("sellerOrders").get(0).get("status").asText()).isEqualTo("AWAITING_FULFILLMENT");
    assertThat(library(buyer)).hasSize(1);
    var fulfillments = api.call(operator, "admin-order-fulfillments", "GET", "/v1/admin/orders/" + orderId + "/fulfillments", null).expect(200).body();
    assertThat(fulfillments).hasSize(1);
  }

  @Test
  void assetsRequireADigitalProductAndForeignStoreEntitlementsAreInvisible() throws Exception {
    productType("poster", "PHYSICAL");
    var physical = product("poster", "poster");
    var refused = api.call(operator, "admin-asset-create", "POST", "/v1/admin/assets", Map.of("productId", physical[0], "kind", "FILE", "title", "x"));
    assertThat(refused.status()).isEqualTo(422);

    var buyer = Caller.operator();
    buy(buyer, pdfOffer);
    String own = library(buyer).get(0).get("entitlementId").asText();
    UUID foreign = UUID.randomUUID();
    try (var c = EngineTestSupport.ownerConnection(); var s = c.createStatement()) {
      s.executeUpdate("insert into digital.entitlement(id, tenant_id, store_id, customer_id, order_id, order_line_id, product_id, asset_id, granted_version, updates_included, downloads_used, status) "
          + "select '" + foreign + "', tenant_id, '" + UUID.randomUUID() + "', customer_id, order_id, gen_random_uuid(), product_id, asset_id, granted_version, updates_included, 0, 'ACTIVE' "
          + "from digital.entitlement where id = '" + own + "'");
    }
    assertThat(download(buyer, foreign.toString()).status()).isEqualTo(404);
    assertThat(library(buyer)).hasSize(1);
  }
}
