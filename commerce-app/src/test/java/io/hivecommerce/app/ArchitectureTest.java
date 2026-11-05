package io.hivecommerce.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.hivecommerce.platform.web.HiveOperation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/** Boundary rules of ADR-0002 and §6.1.4, enforced on every build. */
class ArchitectureTest {
  private static final List<String> CONTEXTS = List.of("store", "catalog", "seller", "listing", "inventory", "pricing", "ordering", "payment", "fulfillment", "checkout", "digital", "finance");
  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("io.hivecommerce");
  }

  @Test
  void domainIsPlainJava() {
    noClasses().that().resideInAPackage("io.hivecommerce..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "com.fasterxml..", "java.sql..", "javax.sql..",
            "io.hiveplatform..", "io.hivecommerce.platform..", "io.hivecommerce..application..", "io.hivecommerce..infrastructure..", "io.hivecommerce..api..")
        .check(classes);
  }

  @Test
  void sharedKernelDependsOnNothingInternal() {
    noClasses().that().resideInAPackage("io.hivecommerce.shared..")
        .should().dependOnClassesThat().resideInAnyPackage("io.hivecommerce.platform..", "io.hivecommerce.store..", "io.hivecommerce.catalog..", "io.hivecommerce.seller..", "io.hivecommerce.listing..", "io.hivecommerce.inventory..", "io.hivecommerce.pricing..", "io.hivecommerce.ordering..", "io.hivecommerce.payment..", "io.hivecommerce.fulfillment..", "io.hivecommerce.checkout..", "io.hivecommerce.digital..", "io.hivecommerce.finance..", "org.springframework..")
        .check(classes);
  }

  @Test
  void contextsTalkOnlyThroughPublishedApis() {
    for (String context : CONTEXTS) {
      for (String other : CONTEXTS) {
        if (context.equals(other)) continue;
        noClasses().that().resideInAPackage("io.hivecommerce." + context + "..")
            .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe("internals of " + other,
                c -> c.getPackageName().startsWith("io.hivecommerce." + other) && !c.getPackageName().startsWith("io.hivecommerce." + other + ".application.published")))
            .check(classes);
      }
    }
  }

  @Test
  void hiveTypesStayInThePlatformAnticorruptionLayer() {
    noClasses().that().resideInAnyPackage("io.hivecommerce.store..", "io.hivecommerce.catalog..", "io.hivecommerce.seller..", "io.hivecommerce.listing..", "io.hivecommerce.inventory..", "io.hivecommerce.pricing..", "io.hivecommerce.ordering..", "io.hivecommerce.payment..", "io.hivecommerce.fulfillment..", "io.hivecommerce.checkout..", "io.hivecommerce.digital..", "io.hivecommerce.finance..")
        .should().dependOnClassesThat().haveFullyQualifiedName("io.hivecommerce.platform.identity.HiveHeaders")
        .check(classes);
    noClasses().that().resideInAnyPackage("io.hivecommerce..domain..", "io.hivecommerce..application..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
        .check(classes);
  }

  @Test
  void controllersOnlyCallApplicationServices() {
    noClasses().that().resideInAPackage("io.hivecommerce..api..")
        .should().dependOnClassesThat().resideInAPackage("io.hivecommerce..infrastructure..")
        .check(classes);
    noClasses().that().resideInAnyPackage("io.hivecommerce..application..", "io.hivecommerce..domain..")
        .should().dependOnClassesThat().resideInAPackage("io.hivecommerce..api..")
        .check(classes);
    classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage("io.hivecommerce..api..").check(classes);
  }

  @Test
  void everyOperatorHandlerDeclaresItsHiveOperation() {
    methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
        .and(com.tngtech.archunit.base.DescribedPredicate.describe("are request handlers",
            m -> m.getAnnotations().stream().anyMatch(a -> a.getRawType().getName().startsWith("org.springframework.web.bind.annotation.") && a.getRawType().getSimpleName().endsWith("Mapping"))))
        .and().areNotAnnotatedWith(io.hivecommerce.platform.web.ProviderCallback.class)
        .should().beAnnotatedWith(HiveOperation.class)
        .check(classes);
  }

  /** Each context's SQL may name only its own schema (ADR-0002: schema isolation without per-context roles). */
  @ParameterizedTest
  @ValueSource(strings = {"commerce-platform:platform", "commerce-store:store", "commerce-catalog:catalog", "commerce-seller:seller", "commerce-listing:listing", "commerce-inventory:inventory", "commerce-ordering:ordering", "commerce-payment:payment", "commerce-fulfillment:fulfillment", "commerce-checkout:checkout", "commerce-digital-delivery:digital", "commerce-finance:finance"})
  void sqlNamesOnlyItsOwnSchema(String moduleAndSchema) throws IOException {
    String module = moduleAndSchema.split(":")[0], own = moduleAndSchema.split(":")[1];
    List<String> foreign = Stream.of("platform", "store", "catalog", "listing", "seller", "pricing", "inventory", "checkout", "ordering", "payment",
        "fulfillment", "digital", "finance").filter(s -> !s.equals(own)).toList();
    Pattern reference = Pattern.compile("\\b(" + String.join("|", foreign) + ")\\.[a-z_]+");
    Path root = Path.of("..", module, "src", "main");
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var sqlLiterals = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:select|insert|update|delete)[^\"]*)\"", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(source);
        String sql = file.toString().endsWith(".sql") ? source : sqlLiterals.results().map(r -> r.group(1) != null ? r.group(1) : r.group(2)).reduce("", String::concat);
        assertThat(reference.matcher(sql).find()).as("%s references another context's schema", file).isFalse();
      }
    }
  }
}
