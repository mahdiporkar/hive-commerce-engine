package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hivecommerce.app.support.EngineTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The committed OpenAPI document ({@code docs/api/openapi.json}) is the engine's API contract (Scenario F). This test
 * fails when the running engine's API differs from it. Regenerate deliberately with
 * {@code ./mvnw test -pl commerce-app -Dtest=OpenApiContractTest -Dopenapi.write=true} and review the diff.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {
  private static final Path CONTRACT = Path.of("..", "docs", "api", "openapi.json");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    EngineTestSupport.register(registry);
    registry.add("springdoc.api-docs.enabled", () -> "true");
    registry.add("commerce.events.dispatch-enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Test
  void apiMatchesTheCommittedContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    String raw = RestClient.create("http://127.0.0.1:" + port).get().uri("/v3/api-docs").retrieve().body(String.class);
    JsonNode live = mapper.readTree(raw);
    ((ObjectNode) live).remove("servers");
    String rendered = mapper.writeValueAsString(mapper.treeToValue(live, Object.class)) + "\n";
    if (Boolean.getBoolean("openapi.write") || !Files.exists(CONTRACT)) {
      Files.createDirectories(CONTRACT.getParent());
      Files.writeString(CONTRACT, rendered);
    }
    assertThat(mapper.readTree(Files.readString(CONTRACT))).isEqualTo(mapper.readTree(rendered));
  }
}
