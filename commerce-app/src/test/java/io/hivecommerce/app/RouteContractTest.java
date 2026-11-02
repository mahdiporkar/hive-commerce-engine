package io.hivecommerce.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.hivecommerce.platform.web.HiveOperation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Hive route-operation template ({@code hive/templates/commerce-service.json}) and the engine's handlers must
 * describe the same API (ADR-0006): same keys, methods and paths, and every {@code /v1/admin/**} operation is
 * AUTHENTICATED with a declared resource action of the {@code commerce-api} manifest.
 */
class RouteContractTest {
  record Operation(String method, String path) {}

  @Test
  void templateAndHandlersAgree() throws IOException {
    JsonNode template = new ObjectMapper().readTree(Path.of("..", "hive", "templates", "commerce-service.json").toFile());
    JsonNode manifest = new ObjectMapper().readTree(Path.of("..", "hive", "templates", "commerce-api", "resource-manifest.json").toFile());
    Map<String, Set<String>> declaredActions = new HashMap<>();
    manifest.get("resources").forEach(r -> {
      Set<String> actions = new HashSet<>();
      r.get("actions").forEach(a -> actions.add(a.isTextual() ? a.asText() : a.get("key").asText()));
      declaredActions.put(r.get("key").asText(), actions);
    });

    Map<String, Operation> fromTemplate = new HashMap<>();
    for (JsonNode op : template.get("operations")) {
      String key = op.get("key").asText(), path = op.get("pathPattern").asText();
      fromTemplate.put(key, new Operation(op.get("method").asText(), path));
      String access = op.get("access").asText();
      if (path.startsWith("/v1/store/")) assertThat(access).as("%s must be PUBLIC", key).isEqualTo("PUBLIC");
      if (path.startsWith("/v1/shop/") || path.startsWith("/v1/seller/")) assertThat(access).as("%s must be HYBRID (ADR-0004)", key).isEqualTo("HYBRID");
      if (path.startsWith("/v1/admin/")) {
        assertThat(op.get("access").asText()).as("%s must be AUTHENTICATED", key).isEqualTo("AUTHENTICATED");
        assertThat(declaredActions.getOrDefault(op.get("resourceKey").asText(), Set.of()))
            .as("%s must name a declared resource action", key).contains(op.get("action").asText());
      }
    }

    Map<String, Operation> fromHandlers = new HashMap<>();
    var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.hivecommerce");
    for (var controller : classes.stream().filter(c -> c.isAnnotatedWith(RestController.class)).toList()) {
      String base = controller.isAnnotatedWith(RequestMapping.class) ? controller.getAnnotationOfType(RequestMapping.class).value()[0] : "";
      for (JavaMethod method : controller.getMethods()) {
        if (!method.isAnnotatedWith(HiveOperation.class)) continue;
        fromHandlers.put(method.getAnnotationOfType(HiveOperation.class).value(), new Operation(httpMethod(method), base + path(method)));
      }
    }
    assertThat(fromHandlers).isEqualTo(fromTemplate);
  }

  private static String httpMethod(JavaMethod m) {
    if (m.isAnnotatedWith(PostMapping.class)) return "POST";
    if (m.isAnnotatedWith(GetMapping.class)) return "GET";
    if (m.isAnnotatedWith(PutMapping.class)) return "PUT";
    if (m.isAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)) return "DELETE";
    throw new IllegalStateException("Unsupported mapping on " + m.getFullName());
  }

  private static String path(JavaMethod m) {
    if (m.isAnnotatedWith(PostMapping.class)) return m.getAnnotationOfType(PostMapping.class).value()[0];
    if (m.isAnnotatedWith(GetMapping.class)) return m.getAnnotationOfType(GetMapping.class).value()[0];
    if (m.isAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)) return m.getAnnotationOfType(org.springframework.web.bind.annotation.DeleteMapping.class).value()[0];
    return m.getAnnotationOfType(PutMapping.class).value()[0];
  }
}
