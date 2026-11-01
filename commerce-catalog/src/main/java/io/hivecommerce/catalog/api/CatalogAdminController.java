package io.hivecommerce.catalog.api;

import io.hivecommerce.catalog.application.CatalogAdminService;
import io.hivecommerce.catalog.application.CatalogCommands;
import io.hivecommerce.catalog.application.CatalogViews.AttributeView;
import io.hivecommerce.catalog.application.CatalogViews.ProductTypeView;
import io.hivecommerce.catalog.application.CatalogViews.ProductView;
import io.hivecommerce.catalog.domain.AttributeConstraints;
import io.hivecommerce.catalog.domain.AttributeFlags;
import io.hivecommerce.catalog.domain.AttributeOption;
import io.hivecommerce.catalog.domain.AttributeType;
import io.hivecommerce.catalog.domain.Product;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.LocalizedText;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator catalog API. Reached only through the Hive BFF as {@code /api/routes/commerce/v1/admin/...}; every handler
 * names its Hive route operation, and the operation's resource action is checked by Hive before the request arrives
 * (docs/architecture/route-operations.md). No business logic here: parse, delegate, return a view.
 */
@RestController
@RequestMapping("/v1/admin")
class CatalogAdminController {
  private final CatalogAdminService catalog;

  CatalogAdminController(CatalogAdminService catalog) {
    this.catalog = catalog;
  }

  record OptionBody(String code, Map<String, String> labels) {}

  record ConstraintsBody(Integer minLength, Integer maxLength, BigDecimal min, BigDecimal max, List<String> units, String referenceType) {}

  record FlagsBody(boolean filterable, boolean searchable, boolean sortable, boolean comparable, Boolean visible) {}

  record AttributeBody(String key, String type, Map<String, String> labels, List<OptionBody> options, ConstraintsBody constraints, FlagsBody flags) {}

  record SpecBody(String key, boolean required, boolean variantDefining) {}

  record ProductTypeBody(String key, Map<String, String> name, List<SpecBody> attributes) {}

  record VariantBody(String sku, Map<String, Object> attributes) {}

  record ProductBody(String productType, Map<String, String> title, String slug, Map<String, Object> attributes, List<VariantBody> variants) {}

  @PostMapping("/attributes")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("admin-attribute-define")
  AttributeView defineAttribute(UserContext user, @RequestBody AttributeBody body) {
    var options = body.options() == null ? List.<AttributeOption>of()
        : body.options().stream().map(o -> new AttributeOption(o.code(), text("options.labels", o.labels()))).toList();
    var c = body.constraints();
    var constraints = c == null ? null : new AttributeConstraints(c.minLength(), c.maxLength(), c.min(), c.max(), c.units(), c.referenceType());
    var f = body.flags();
    var flags = f == null ? null : new AttributeFlags(f.filterable(), f.searchable(), f.sortable(), f.comparable(), f.visible() == null || f.visible());
    return catalog.defineAttribute(user, new CatalogCommands.DefineAttribute(body.key(), type(body.type()), text("labels", body.labels()), options, constraints, flags));
  }

  @PostMapping("/product-types")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("admin-product-type-create")
  ProductTypeView createProductType(UserContext user, @RequestBody ProductTypeBody body) {
    var specs = body.attributes() == null ? null
        : body.attributes().stream().map(s -> new CatalogCommands.SpecReference(s.key(), s.required(), s.variantDefining())).toList();
    return catalog.createProductType(user, new CatalogCommands.CreateProductType(body.key(), text("name", body.name()), specs));
  }

  @PostMapping("/product-types/{productTypeKey}/versions/{version}/publish")
  @HiveOperation("admin-product-type-publish")
  ProductTypeView publishSchemaVersion(UserContext user, @PathVariable String productTypeKey, @PathVariable int version) {
    return catalog.publishSchemaVersion(user, productTypeKey, version);
  }

  @PostMapping("/products")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("admin-product-create")
  ProductView createProduct(UserContext user, @RequestBody ProductBody body) {
    var variants = body.variants() == null ? null
        : body.variants().stream().map(v -> v == null ? null : new Product.VariantDraft(v.sku(), v.attributes())).toList();
    return catalog.createProduct(user, new CatalogCommands.CreateProduct(body.productType(), text("title", body.title()), body.slug(), body.attributes(), variants));
  }

  @GetMapping("/products/{productId}")
  @HiveOperation("admin-product-get")
  ProductView getProduct(UserContext user, @PathVariable UUID productId) {
    return catalog.getProduct(user, productId);
  }

  private static AttributeType type(String value) {
    try {
      return AttributeType.valueOf(String.valueOf(value));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("type", "must be one of " + List.of(AttributeType.values()));
    }
  }

  private static LocalizedText text(String field, Map<String, String> values) {
    if (values == null) throw DomainValidationException.of(field, "is required");
    try {
      return new LocalizedText(values);
    } catch (DomainValidationException e) {
      throw DomainValidationException.of(field, e.violations().getFirst().field() + " " + e.violations().getFirst().message());
    }
  }
}
