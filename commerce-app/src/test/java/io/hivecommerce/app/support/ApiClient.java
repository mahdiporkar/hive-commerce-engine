package io.hivecommerce.app.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Calls the engine exactly as the Hive BFF forwards requests: user token, Hive identity headers and the route
 * operation key. {@link Caller#anonymous()} sends no identity (PUBLIC / HYBRID operations).
 */
public final class ApiClient {
  public record Caller(String token, UUID hiveUserId, String tenant) {
    public static Caller anonymous() { return new Caller(null, null, null); }

    /** A new signed-in shopper. */
    public static Caller customer() { return operator(); }

    public static Caller operator() {
      return new Caller(EngineTestSupport.IDP.token("kc-" + UUID.randomUUID()), UUID.randomUUID(), "default");
    }
  }

  public record Response(int status, JsonNode body) {
    public String code() { return body == null || body.get("code") == null ? null : body.get("code").asText(); }

    public Response expect(int expected) {
      if (status != expected) throw new AssertionError("expected HTTP " + expected + " but was " + status + ": " + body);
      return this;
    }
  }

  private final RestClient http;
  private final ObjectMapper json;

  public ApiClient(int port, ObjectMapper json) {
    this.json = json;
    this.http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (req, res) -> {}).build();
  }

  public Response call(Caller caller, String operation, String method, String path, Object body) {
    var request = http.method(HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON);
    if (caller.token() != null) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    if (caller.hiveUserId() != null) request = request.header("X-Hive-User-Id", caller.hiveUserId().toString());
    if (caller.tenant() != null) request = request.header("X-Hive-Tenant-Id", caller.tenant());
    if (operation != null) request = request.header("X-Hive-Route", "commerce").header("X-Hive-Operation", operation);
    if (body != null) request = request.body(body);
    return request.exchange((req, res) -> {
      byte[] bytes = res.getBody().readAllBytes();
      return new Response(res.getStatusCode().value(), bytes.length == 0 ? null : json.readTree(bytes));
    });
  }
}
