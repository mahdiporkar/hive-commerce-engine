package io.hivecommerce.app;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * One Flyway history per bounded-context schema (ADR-0002), applied with the owner role before the runtime
 * DataSource is used (ADR-0009: the runtime role owns nothing and cannot bypass row-level security). Spring Boot
 * orders JDBC beans after every {@link FlywayMigrationInitializer}.
 */
@Configuration
class SchemaMigrations {
  private final String url, user, password, runtimeRole;

  SchemaMigrations(@Value("${commerce.migration.url}") String url, @Value("${commerce.migration.username}") String user,
      @Value("${commerce.migration.password}") String password, @Value("${commerce.migration.runtime-role}") String runtimeRole) {
    this.url = url;
    this.user = user;
    this.password = password;
    this.runtimeRole = runtimeRole;
  }

  @Bean
  FlywayMigrationInitializer platformSchema() { return initializer("platform"); }

  @Bean
  @DependsOn("platformSchema")
  FlywayMigrationInitializer storeSchema() { return initializer("store"); }

  @Bean
  @DependsOn("storeSchema")
  FlywayMigrationInitializer catalogSchema() { return initializer("catalog"); }

  @Bean
  @DependsOn("catalogSchema")
  FlywayMigrationInitializer sellerSchema() { return initializer("seller"); }

  @Bean
  @DependsOn("sellerSchema")
  FlywayMigrationInitializer listingSchema() { return initializer("listing"); }

  @Bean
  @DependsOn("listingSchema")
  FlywayMigrationInitializer inventorySchema() { return initializer("inventory"); }

  @Bean
  @DependsOn("inventorySchema")
  FlywayMigrationInitializer orderingSchema() { return initializer("ordering"); }

  @Bean
  @DependsOn("orderingSchema")
  FlywayMigrationInitializer paymentSchema() { return initializer("payment"); }

  @Bean
  @DependsOn("paymentSchema")
  FlywayMigrationInitializer fulfillmentSchema() { return initializer("fulfillment"); }

  @Bean
  @DependsOn("fulfillmentSchema")
  FlywayMigrationInitializer checkoutSchema() { return initializer("checkout"); }

  @Bean
  @DependsOn("checkoutSchema")
  FlywayMigrationInitializer digitalSchema() { return initializer("digital"); }


  private FlywayMigrationInitializer initializer(String schema) {
    Flyway flyway = Flyway.configure()
        .dataSource(url, user, password)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .locations("classpath:db/migration/" + schema)
        .placeholders(Map.of("runtime_role", runtimeRole))
        .validateMigrationNaming(true)
        .load();
    return new FlywayMigrationInitializer(flyway);
  }
}
