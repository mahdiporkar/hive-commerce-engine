package io.hivecommerce.payment.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.payment.domain.PaymentProvider;
import io.hivecommerce.shared.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * FAKE payment provider for development and tests only (master prompt §12 rule 13). It simulates a PSP: sessions are
 * approved or declined through {@code /hooks/fake-psp/...}, verification reads the session, and webhooks are signed
 * with HMAC-SHA256 ({@code X-Fake-Signature}). The ProductionGuard refuses to start when it is enabled.
 */
@Component
@ConditionalOnProperty(name = "commerce.payments.fake.enabled", havingValue = "true")
public class FakePaymentProvider implements PaymentProvider, PaymentProvider.Simulator {
  public static final String KEY = "fake";
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final byte[] secret;
  private final String pspBaseUrl;

  FakePaymentProvider(JdbcClient jdbc, ObjectMapper json, @Value("${commerce.payments.fake.webhook-secret}") String secret,
      @Value("${commerce.payments.fake.psp-base-url:http://fake-psp.invalid}") String pspBaseUrl) {
    if (secret == null || secret.length() < 16) throw new IllegalStateException("commerce.payments.fake.webhook-secret must have at least 16 characters");
    this.jdbc = jdbc;
    this.json = json;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.pspBaseUrl = pspBaseUrl;
  }

  @Override public String key() { return KEY; }

  @Override public boolean testOnly() { return true; }

  @Override
  public Initiation initiate(UUID paymentId, Money amount, String returnUrl) {
    String ref = "fake_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.sql("insert into payment.fake_psp_session(ref, payment_id, amount, currency, return_url, status) values (:ref, :payment, :amount, :currency, :return, 'PENDING')")
        .param("ref", ref).param("payment", paymentId).param("amount", amount.amount()).param("currency", amount.currency().getCurrencyCode())
        .param("return", returnUrl).update();
    return new Initiation(ref, pspBaseUrl + "/pay/" + ref);
  }

  @Override
  public Verification verify(String ref) {
    return jdbc.sql("select status, amount, currency, tx_id from payment.fake_psp_session where ref = :ref").param("ref", ref)
        .query((rs, n) -> new Verification(switch (rs.getString("status")) { case "APPROVED" -> Outcome.PAID; case "DECLINED" -> Outcome.DECLINED; default -> Outcome.PENDING; },
            rs.getString("tx_id"), new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency")))))
        .optional().orElse(new Verification(Outcome.DECLINED, null, null));
  }

  @Override
  public WebhookEvent parseWebhook(Map<String, String> headers, byte[] body) {
    String signature = headers.getOrDefault("x-fake-signature", "");
    if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidWebhookException("invalid signature");
    }
    try {
      JsonNode event = json.readTree(body);
      String ref = event.get("ref").asText();
      // A real adapter would map the callback; the fake trusts its own signed payload but still reports the session amount.
      var verification = verify(ref);
      var outcome = Outcome.valueOf(event.get("outcome").asText());
      return new WebhookEvent(event.get("eventId").asText(), ref, new Verification(outcome, verification.providerTxId(), verification.amount()));
    } catch (InvalidWebhookException e) {
      throw e;
    } catch (Exception e) {
      throw new InvalidWebhookException("malformed callback");
    }
  }

  @Override
  public String refund(String providerTxId, Money amount, String idempotencyKey) {
    return "fake_refund_" + HexFormat.of().formatHex(sha256((providerTxId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
  }

  /** Simulates the shopper completing (or abandoning) payment on the PSP's page. */
  @Override
  public String decide(String ref, boolean approve) {
    String tx = approve ? "fake_tx_" + UUID.randomUUID().toString().replace("-", "") : null;
    int updated = jdbc.sql("update payment.fake_psp_session set status = :status, tx_id = :tx where ref = :ref and status = 'PENDING'")
        .param("status", approve ? "APPROVED" : "DECLINED").param("tx", tx).param("ref", ref).update();
    if (updated != 1) throw new IllegalArgumentException("unknown or completed session");
    return jdbc.sql("select return_url from payment.fake_psp_session where ref = :ref").param("ref", ref).query(String.class).single();
  }

  public String sign(byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
