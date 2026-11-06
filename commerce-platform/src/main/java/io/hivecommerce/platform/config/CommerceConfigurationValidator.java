package io.hivecommerce.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Validates the client configuration against its versioned JSON Schema at startup (ADR-0014). Any violation stops the
 * engine and every violation is listed. Secrets are not part of the validated document.
 */
@Component
public class CommerceConfigurationValidator {
  public static final String SCHEMA_VERSION = "1.0.0";
  private final Environment env;
  private final ObjectMapper mapper;

  public CommerceConfigurationValidator(Environment env, ObjectMapper mapper) {
    this.env = env;
    this.mapper = mapper;
  }

  @PostConstruct
  void validate() {
    Set<ValidationMessage> errors = validate(document());
    if (!errors.isEmpty()) {
      throw new IllegalStateException("Invalid commerce configuration (schema " + SCHEMA_VERSION + "): "
          + errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("; ")));
    }
  }

  public Set<ValidationMessage> validate(ObjectNode document) {
    try (InputStream schema = getClass().getResourceAsStream("/config/schema/commerce-config-" + SCHEMA_VERSION + ".json")) {
      JsonSchema compiled = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
      return compiled.validate(document);
    } catch (IOException e) {
      throw new IllegalStateException("Configuration schema missing", e);
    }
  }

  /** The non-secret configuration as a JSON document, in the shape of the schema. */
  ObjectNode document() {
    ObjectNode root = mapper.createObjectNode();
    root.put("configSchemaVersion", env.getProperty("commerce.config-schema-version", SCHEMA_VERSION));
    root.put("tenantId", env.getProperty("commerce.tenant-id", ""));
    ObjectNode store = root.putObject("store");
    store.put("key", env.getProperty("commerce.store.key", ""));
    store.put("mode", env.getProperty("commerce.store.mode", ""));
    store.put("defaultCurrency", env.getProperty("commerce.store.default-currency", ""));
    var locales = store.putArray("locales");
    Arrays.stream(env.getProperty("commerce.store.locales", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(locales::add);
    ObjectNode hive = root.putObject("hive");
    hive.put("routeKey", env.getProperty("commerce.hive.route-key", ""));
    ObjectNode capabilities = root.putObject("capabilities");
    for (String capability : new String[] {"physical-products", "digital-products", "seller-onboarding", "commissions", "reviews"}) {
      String value = env.getProperty("commerce.capabilities." + capability);
      String key = toCamel(capability);
      if (value == null) continue;
      if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) capabilities.put(key, Boolean.parseBoolean(value));
      else capabilities.put(key, value);
    }
    ObjectNode security = root.putObject("security");
    security.put("issuer", env.getProperty("commerce.security.jwt.issuer", ""));
    security.put("audience", env.getProperty("commerce.security.jwt.audience", ""));
    return root;
  }

  private static String toCamel(String kebab) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (char c : kebab.toCharArray()) {
      if (c == '-') upper = true;
      else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return out.toString();
  }
}
