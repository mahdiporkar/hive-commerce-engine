package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.app.support.ApiClient;
import io.hivecommerce.app.support.ApiClient.Caller;
import io.hivecommerce.app.support.EngineTestSupport;
import io.hivecommerce.platform.events.OutboxDispatcher;
import java.math.BigDecimal;
import java.sql.SQLException;
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
 * Phase 4 marketplace: Scenario C (two sellers publish prompts; one cart buys from both; separate seller orders,
 * commissions and ledger entries; neither seller sees the other's data), settlements and payouts, refunds after payout,
 * staff capabilities, suspension. Layer 1 for operators is covered by the through-Hive suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketplaceIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("commerce.store.key", () -> "marketplace-store");
    registry.add("commerce.store.mode", () -> "MULTI_VENDOR");
    registry.add("commerce.capabilities.seller-onboarding", () -> "true");
    registry.add("commerce.capabilities.commissions", () -> "true");
    registry.add("commerce.finance.settlement-hold", () -> "PT0S");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
    registry.add("commerce.inventory.expiry-interval-ms", () -> "3600000");
    registry.add("commerce.payments.sweep-interval-ms", () -> "3600000");
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired OutboxDispatcher dispatcher;
  ApiClient api;
  RestClient hooks;
  final Caller operator = Caller.operator();
  final Caller ownerA = Caller.operator();
  final Caller ownerB = Caller.operator();
  String sellerA, sellerB, offerA, offerB, productA;

  /** Applies, then a moderator verifies and approves. */
  private String onboard(Caller owner, String name) {
    String id = api.call(owner, "seller-apply", "POST", "/v1/seller/applications", Map.of("displayName", Map.of("en", name))).expect(201).body().get("sellerId").asText();
    api.call(operator, "admin-seller-verify", "POST", "/v1/admin/sellers/" + id + "/verify", null).expect(200);
    api.call(operator, "admin-seller-approve", "POST", "/v1/admin/sellers/" + id + "/approve", null).expect(200);
    return id;
  }

  /** Seller submits a product; the moderator approves it, which publishes it. Returns the product id. */
  private String submitAndApprove(Caller owner, String sellerId, String type, String slug) {
    var listing = api.call(owner, "seller-submission", "POST", "/v1/seller/sellers/" + sellerId + "/submissions", Map.of("productType", type, "title", Map.of("en", slug), "slug", slug,
        "attributes", Map.of(), "variants", List.of(Map.of("sku", slug.toUpperCase(), "attributes", Map.of())))).expect(201).body();
    assertThat(listing.get("status").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/" + slug, null).status()).isEqualTo(404);
    api.call(operator, "admin-listing-approve", "POST", "/v1/admin/listings/" + listing.get("listingId").asText() + "/approve", null).expect(200);
    return listing.get("productId").asText();
  }

  private String variantOf(String slug) {
    return api.call(Caller.anonymous(), "store-product-detail", "GET", "/v1/store/products/" + slug, null).expect(200).body().get("product").get("variants").get(0).get("variantId").asText();
  }

  private void productType(String key, String kind) {
    api.call(operator, "admin-product-type-create", "POST", "/v1/admin/product-types", Map.of("key", key, "name", Map.of("en", key), "kind", kind, "attributes", List.of())).expect(201);
    api.call(operator, "admin-product-type-publish", "POST", "/v1/admin/product-types/" + key + "/versions/1/publish", null).expect(200);
  }

  private String promptProduct(Caller owner, String seller, String slug, String content, String price) {
    String product = submitAndApprove(owner, seller, "ai-prompt", slug);
    String asset = api.call(owner, "seller-asset-create", "POST", "/v1/seller/sellers/" + seller + "/assets", Map.of("productId", product, "kind", "TEXT", "title", slug)).expect(201).body().get("assetId").asText();
    api.call(owner, "seller-asset-text", "PUT", "/v1/seller/assets/" + asset + "/text", Map.of("content", content)).expect(200);
    return api.call(owner, "seller-offer-create", "POST", "/v1/seller/sellers/" + seller + "/offers", Map.of("variantId", variantOf(slug), "amount", price, "currency", "IRR")).expect(201).body().get("offerId").asText();
  }

  @BeforeAll
  void createMarketplace() {
    api = new ApiClient(port, json);
    hooks = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (req, res) -> {}).build();
    productType("ai-prompt", "DIGITAL");
    productType("craft", "PHYSICAL");
    sellerA = onboard(ownerA, "Seller A");
    sellerB = onboard(ownerB, "Seller B");
    api.call(operator, "admin-commission-rule-create", "POST", "/v1/admin/finance/commission-rules", Map.of("level", "STORE", "ratePercent", 10)).expect(201);
    api.call(operator, "admin-commission-rule-create", "POST", "/v1/admin/finance/commission-rules", Map.of("level", "SELLER", "targetId", sellerB, "ratePercent", 20)).expect(201);
    offerA = promptProduct(ownerA, sellerA, "prompt-a", "SECRET A", "1000000");
    offerB = promptProduct(ownerB, sellerB, "prompt-b", "SECRET B", "2000000");
    productA = "prompt-a";
    dispatcher.dispatchAll();
  }

  private JsonNode buy(Caller shopper, String... offers) {
    for (String offer : offers) api.call(shopper, "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offer, "quantity", 1)).expect(200);
    var checkout = api.call(shopper, "shop-checkout", "POST", "/v1/shop/checkouts", Map.of("idempotencyKey", "market-" + UUID.randomUUID())).expect(201).body();
    String url = checkout.get("redirectUrl").asText();
    hooks.post().uri("/hooks/fake-psp/sessions/" + url.substring(url.lastIndexOf('/') + 1) + "/approve").retrieve().toBodilessEntity();
    api.call(shopper, "shop-payment-verify", "POST", "/v1/shop/payments/" + checkout.get("paymentId").asText() + "/verify", null).expect(200);
    dispatcher.dispatchAll();
    return checkout;
  }

  private BigDecimal ledger(String account) {
    var accounts = api.call(operator, "admin-finance-balances", "GET", "/v1/admin/finance/balances?currency=IRR", null).expect(200).body().get("accounts");
    return accounts.has(account) ? accounts.get(account).decimalValue() : BigDecimal.ZERO;
  }

  private void assertLedgerBalanced() {
    assertThat(api.call(operator, "admin-finance-balances", "GET", "/v1/admin/finance/balances?currency=IRR", null).expect(200).body().get("sum").decimalValue()).isEqualByComparingTo("0");
  }

  private JsonNode finance(Caller caller, String seller) {
    return api.call(caller, "seller-finance", "GET", "/v1/seller/sellers/" + seller + "/finance?currency=IRR", null).expect(200).body();
  }

  @Test
  void scenarioC_oneCartTwoSellersSeparateOrdersCommissionsAndLedger() {
    var buyer = Caller.operator();
    BigDecimal commissionBefore = ledger("COMMISSION_REVENUE");
    var checkout = buy(buyer, offerA, offerB);
    String orderId = checkout.get("orderId").asText();
    var order = api.call(buyer, "shop-order", "GET", "/v1/shop/orders/" + orderId, null).expect(200).body();
    assertThat(order.get("sellerOrders")).hasSize(2);
    assertThat(order.get("status").asText()).isEqualTo("COMPLETED"); // digital lines are delivered on grant
    assertThat(api.call(buyer, "shop-library", "GET", "/v1/shop/library", null).expect(200).body()).hasSize(2);

    // Commissions: 10 % of 1,000,000 for A (store rule) and 20 % of 2,000,000 for B (seller rule). Revenue is credit-normal.
    assertThat(commissionBefore.subtract(ledger("COMMISSION_REVENUE"))).isEqualByComparingTo("500000");
    assertLedgerBalanced();

    // Each seller sees exactly its own part; the customer's identity is not exposed.
    var partsA = api.call(ownerA, "seller-orders", "GET", "/v1/seller/sellers/" + sellerA + "/orders", null).expect(200).body();
    var mine = java.util.stream.StreamSupport.stream(partsA.spliterator(), false).filter(p -> p.get("orderId").asText().equals(orderId)).toList();
    assertThat(mine).hasSize(1);
    assertThat(mine.getFirst().get("sellerOrder").get("sellerId").asText()).isEqualTo(sellerA);
    assertThat(mine.getFirst().toString()).doesNotContain(sellerB).doesNotContain("customerId").doesNotContain("prompt-b");
  }

  @Test
  void sellersCannotTouchEachOthersData() {
    String offers = "/v1/seller/sellers/" + sellerA;
    for (var attempt : List.of(
        api.call(ownerB, "seller-offers", "GET", offers + "/offers", null),
        api.call(ownerB, "seller-orders", "GET", offers + "/orders", null),
        api.call(ownerB, "seller-finance", "GET", offers + "/finance?currency=IRR", null),
        api.call(ownerB, "seller-listings", "GET", offers + "/listings", null),
        api.call(ownerB, "seller-fulfillments", "GET", offers + "/fulfillments", null),
        api.call(ownerB, "seller-offer-create", "POST", offers + "/offers", Map.of("variantId", variantOf("prompt-b"), "amount", "1", "currency", "IRR")),
        api.call(ownerB, "seller-staff-grant", "POST", offers + "/staff", Map.of("actorId", UUID.randomUUID().toString())),
        api.call(ownerB, "seller-payout-request", "POST", offers + "/payouts", Map.of("settlementId", UUID.randomUUID().toString())))) {
      assertThat(attempt.status()).isEqualTo(404);
    }
    // A plain customer is not a seller either.
    assertThat(api.call(Caller.operator(), "seller-orders", "GET", offers + "/orders", null).status()).isEqualTo(404);
    // Another seller's asset cannot be read or changed.
    String assetOfA = firstAsset("prompt-a");
    assertThat(api.call(ownerB, "seller-asset-text", "PUT", "/v1/seller/assets/" + assetOfA + "/text", Map.of("content", "hijack")).status()).isEqualTo(404);
    assertThat(api.call(ownerB, "seller-asset-preview", "PUT", "/v1/seller/assets/" + assetOfA + "/preview", Map.of("preview", "hijack")).status()).isEqualTo(404);
  }

  private String firstAsset(String slug) {
    try (var c = EngineTestSupport.ownerConnection(); var rs = c.createStatement().executeQuery(
        "select a.id from digital.digital_asset a join catalog.product p on p.id = a.product_id where p.slug = '" + slug + "'")) {
      rs.next();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void settlementPayoutAndARefundAfterPayoutNeverPayTwice() {
    var seller = Caller.operator();
    String id = onboard(seller, "Payout seller");
    String offer = promptProduct(seller, id, "prompt-payout", "PAYOUT CONTENT", "1000000");
    dispatcher.dispatchAll();
    var buyer = Caller.operator();
    var checkout = buy(buyer, offer);
    String orderId = checkout.get("orderId").asText();

    var before = finance(seller, id);
    assertThat(before.get("pending").decimalValue()).isEqualByComparingTo("900000"); // 1,000,000 less 10 %
    assertThat(before.get("available").decimalValue()).isEqualByComparingTo("0");
    var settlement = api.call(operator, "admin-settlement-close", "POST", "/v1/admin/finance/settlements", Map.of("sellerId", id)).expect(201).body();
    assertThat(settlement.get("amount").decimalValue()).isEqualByComparingTo("900000");
    assertThat(finance(seller, id).get("available").decimalValue()).isEqualByComparingTo("900000");
    assertThat(api.call(operator, "admin-settlement-close", "POST", "/v1/admin/finance/settlements", Map.of("sellerId", id)).code()).isEqualTo("NOTHING_TO_SETTLE");

    String settlementId = settlement.get("settlementId").asText();
    var payout = api.call(seller, "seller-payout-request", "POST", "/v1/seller/sellers/" + id + "/payouts", Map.of("settlementId", settlementId)).expect(201).body();
    assertThat(api.call(seller, "seller-payout-request", "POST", "/v1/seller/sellers/" + id + "/payouts", Map.of("settlementId", settlementId)).code()).isEqualTo("PAYOUT_EXISTS");
    String payoutId = payout.get("payoutId").asText();
    api.call(operator, "admin-payout-approve", "POST", "/v1/admin/finance/payouts/" + payoutId + "/approve", null).expect(200);
    assertThat(api.call(operator, "admin-payout-approve", "POST", "/v1/admin/finance/payouts/" + payoutId + "/approve", null).code()).isEqualTo("PAYOUT_STATUS_INVALID");
    api.call(operator, "admin-payout-paid", "POST", "/v1/admin/finance/payouts/" + payoutId + "/paid", Map.of("note", "BANK-REF-1")).expect(200);
    assertThat(finance(seller, id).get("available").decimalValue()).isEqualByComparingTo("0");
    assertLedgerBalanced();

    // The buyer is refunded after the payout: the seller's balance goes negative and nothing is paid twice.
    var order = api.call(operator, "admin-order", "GET", "/v1/admin/orders/" + orderId, null).expect(200).body();
    String lineId = order.get("sellerOrders").get(0).get("lines").get(0).get("lineId").asText();
    api.call(operator, "admin-order-refund", "POST", "/v1/admin/orders/" + orderId + "/refunds",
        Map.of("idempotencyKey", "refund-" + UUID.randomUUID(), "amount", "1000000", "orderLineIds", List.of(lineId))).expect(200);
    dispatcher.dispatchAll();
    assertThat(finance(seller, id).get("available").decimalValue()).isEqualByComparingTo("-900000");
    assertThat(api.call(seller, "seller-payout-request", "POST", "/v1/seller/sellers/" + id + "/payouts", Map.of("settlementId", settlementId)).code()).isEqualTo("PAYOUT_EXISTS");
    assertThat(api.call(operator, "admin-payouts", "GET", "/v1/admin/finance/payouts?sellerId=" + id, null).expect(200).body()).hasSize(1);
    assertThat(api.call(buyer, "shop-library", "GET", "/v1/shop/library", null).expect(200).body().get(0).get("status").asText()).isEqualTo("REVOKED");
    assertLedgerBalanced();

    // A later sale is netted against the debt: only what remains can be paid out.
    var nextBuyer = Caller.operator();
    buy(nextBuyer, offer);
    var next = api.call(operator, "admin-settlement-close", "POST", "/v1/admin/finance/settlements", Map.of("sellerId", id)).expect(201).body();
    assertThat(next.get("amount").decimalValue()).isEqualByComparingTo("900000");
    assertThat(finance(seller, id).get("available").decimalValue()).isEqualByComparingTo("0");
    assertThat(api.call(seller, "seller-payout-request", "POST", "/v1/seller/sellers/" + id + "/payouts", Map.of("settlementId", next.get("settlementId").asText())).code()).isEqualTo("NO_AVAILABLE_BALANCE");
  }

  @Test
  void physicalSellerShipsOwnOrdersOnly() {
    var seller = Caller.operator();
    String id = onboard(seller, "Craft seller");
    submitAndApprove(seller, id, "craft", "craft-bowl");
    String offer = api.call(seller, "seller-offer-create", "POST", "/v1/seller/sellers/" + id + "/offers",
        Map.of("variantId", variantOf("craft-bowl"), "amount", "500000", "currency", "IRR", "quantity", 3)).expect(201).body().get("offerId").asText();
    dispatcher.dispatchAll();
    var buyer = Caller.operator();
    var checkout = buy(buyer, offer);
    var fulfillments = api.call(seller, "seller-fulfillments", "GET", "/v1/seller/sellers/" + id + "/fulfillments", null).expect(200).body();
    String fulfillmentId = fulfillments.get(0).get("fulfillmentId").asText();
    assertThat(api.call(ownerB, "seller-fulfillment-ship", "POST", "/v1/seller/fulfillments/" + fulfillmentId + "/ship", Map.of("carrier", "x")).status()).isEqualTo(404);
    api.call(seller, "seller-fulfillment-ship", "POST", "/v1/seller/fulfillments/" + fulfillmentId + "/ship", Map.of("carrier", "Iran Post", "trackingCode", "T1")).expect(200);
    dispatcher.dispatchAll();
    var order = api.call(buyer, "shop-order", "GET", "/v1/shop/orders/" + checkout.get("orderId").asText(), null).expect(200).body();
    assertThat(order.get("sellerOrders").get(0).get("status").asText()).isEqualTo("SHIPPED");
    // Not delivered yet: nothing can be settled for it.
    assertThat(api.call(operator, "admin-settlement-close", "POST", "/v1/admin/finance/settlements", Map.of("sellerId", id)).code()).isEqualTo("NOTHING_TO_SETTLE");
  }

  @Test
  void staffCapabilitiesAreEnforcedAndRevocable() {
    var staff = Caller.operator();
    api.call(ownerA, "seller-staff-grant", "POST", "/v1/seller/sellers/" + sellerA + "/staff", Map.of("actorId", staff.hiveUserId().toString(), "capabilities", List.of("orders.view"))).expect(200);
    assertThat(api.call(staff, "seller-me", "GET", "/v1/seller/me", null).expect(200).body()).hasSize(1);
    api.call(staff, "seller-orders", "GET", "/v1/seller/sellers/" + sellerA + "/orders", null).expect(200);
    assertThat(api.call(staff, "seller-finance", "GET", "/v1/seller/sellers/" + sellerA + "/finance?currency=IRR", null).status()).isEqualTo(404);
    assertThat(api.call(staff, "seller-offers", "GET", "/v1/seller/sellers/" + sellerA + "/offers", null).status()).isEqualTo(404);
    api.call(ownerA, "seller-staff-revoke", "DELETE", "/v1/seller/sellers/" + sellerA + "/staff/" + staff.hiveUserId(), null).expect(200);
    assertThat(api.call(staff, "seller-orders", "GET", "/v1/seller/sellers/" + sellerA + "/orders", null).status()).isEqualTo(404);
    assertThat(api.call(ownerA, "seller-staff-revoke", "DELETE", "/v1/seller/sellers/" + sellerA + "/staff/" + ownerA.hiveUserId(), null).code()).isEqualTo("LAST_OWNER");
  }

  @Test
  void suspensionWithdrawsOffersAndHoldsPayouts() {
    var seller = Caller.operator();
    String id = onboard(seller, "Suspended seller");
    String offer = promptProduct(seller, id, "prompt-suspended", "CONTENT", "1000000");
    dispatcher.dispatchAll();
    buy(Caller.operator(), offer);
    String settlementId = api.call(operator, "admin-settlement-close", "POST", "/v1/admin/finance/settlements", Map.of("sellerId", id)).expect(201).body().get("settlementId").asText();
    String payoutId = api.call(seller, "seller-payout-request", "POST", "/v1/seller/sellers/" + id + "/payouts", Map.of("settlementId", settlementId)).expect(201).body().get("payoutId").asText();

    api.call(operator, "admin-seller-suspend", "POST", "/v1/admin/sellers/" + id + "/suspend", null).expect(200);
    dispatcher.dispatchAll();
    assertThat(api.call(Caller.operator(), "shop-cart-add", "POST", "/v1/shop/carts/current/items", Map.of("offerId", offer, "quantity", 1)).code()).isEqualTo("OFFER_UNAVAILABLE");
    assertThat(api.call(operator, "admin-payout-approve", "POST", "/v1/admin/finance/payouts/" + payoutId + "/approve", null).code()).isEqualTo("SELLER_NOT_ACTIVE");
    assertThat(api.call(seller, "seller-offers", "GET", "/v1/seller/sellers/" + id + "/offers", null).status()).isEqualTo(404);
    api.call(operator, "admin-seller-reinstate", "POST", "/v1/admin/sellers/" + id + "/reinstate", null).expect(200);
    api.call(operator, "admin-payout-approve", "POST", "/v1/admin/finance/payouts/" + payoutId + "/approve", null).expect(200);
  }

  @Test
  void theLedgerIsAppendOnlyAndBalancedInTheDatabase() throws SQLException {
    buy(Caller.operator(), offerA);
    try (var c = EngineTestSupport.ownerConnection(); var s = c.createStatement()) {
      assertThatThrownBy(() -> s.executeUpdate("update finance.journal_line set debit = debit + 1 where debit > 0")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
      assertThatThrownBy(() -> s.executeUpdate("delete from finance.journal_entry")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
      UUID entry = UUID.randomUUID();
      String store = "(select store_id from finance.journal_entry limit 1)";
      assertThatThrownBy(() -> s.execute("begin; insert into finance.journal_entry(id, tenant_id, store_id, type, source_id, currency) values ('" + entry + "', 'default', " + store + ", 'CASH_IN', 'bad', 'IRR');"
          + "insert into finance.journal_line(id, tenant_id, store_id, entry_id, account, debit, credit, currency) values (gen_random_uuid(), 'default', " + store + ", '" + entry + "', 'CASH', 5, 0, 'IRR'); commit;"))
          .isInstanceOf(SQLException.class).hasMessageContaining("not balanced");
    }
    try (var c = EngineTestSupport.runtimeConnection(); var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("select set_config('app.tenant_id', 'default', true), set_config('app.store_id', '" + UUID.randomUUID() + "', true)");
      assertThatThrownBy(() -> s.executeUpdate("update finance.journal_line set debit = 0")).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
      c.rollback();
    }
  }
}
