package io.hivecommerce.app;

import io.hivecommerce.platform.identity.UserContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the engine API (master prompt §20). Served only when {@code springdoc.api-docs.enabled=true};
 * the committed contract is {@code docs/api/openapi.json}, checked by {@code OpenApiContractTest}. Paths are the
 * engine's upstream paths; browsers reach them as {@code /api/routes/commerce/...} through the Hive BFF.
 */
@Configuration
class OpenApiConfiguration {
  static {
    // UserContext is resolved from the Hive identity, never from request parameters.
    SpringDocUtils.getConfig().addRequestWrapperToIgnore(UserContext.class);
  }

  @Bean
  OpenAPI commerceOpenApi() {
    return new OpenAPI().info(new Info().title("Hive Commerce Engine API").version("v1")
        .description("Reached through the Hive BFF at /api/routes/commerce. Access per operation: hive/templates/commerce-service.json."));
  }
}
