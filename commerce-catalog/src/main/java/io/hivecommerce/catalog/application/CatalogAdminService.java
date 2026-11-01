package io.hivecommerce.catalog.application;

import io.hivecommerce.catalog.application.CatalogCommands.CreateProduct;
import io.hivecommerce.catalog.application.CatalogCommands.CreateProductType;
import io.hivecommerce.catalog.application.CatalogCommands.DefineAttribute;
import io.hivecommerce.catalog.application.CatalogViews.AttributeView;
import io.hivecommerce.catalog.application.CatalogViews.ProductTypeView;
import io.hivecommerce.catalog.application.CatalogViews.ProductView;
import io.hivecommerce.catalog.domain.AttributeDefinition;
import io.hivecommerce.catalog.domain.AttributeSpec;
import io.hivecommerce.catalog.domain.CatalogRepositories;
import io.hivecommerce.catalog.domain.Product;
import io.hivecommerce.catalog.domain.ProductType;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Operator use cases of the Catalog context. Layer 1 (Hive) has already checked the operator's resource action at the
 * BFF; Layer 2 here requires an authenticated actor of the deployment's tenant and scopes every read and write to the
 * deployment store (ADR-0016 §4). Each use case changes one aggregate and appends its events to the context outbox in
 * the same transaction.
 */
@Service
public class CatalogAdminService {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final CatalogRepositories.AttributeDefinitions attributes;
  private final CatalogRepositories.ProductTypes productTypes;
  private final CatalogRepositories.Products products;
  private final CatalogRepositories.Outbox outbox;
  private final Clock clock;

  public CatalogAdminService(DeploymentScope deployment, TenantTransactions transactions, CatalogRepositories.AttributeDefinitions attributes,
      CatalogRepositories.ProductTypes productTypes, CatalogRepositories.Products products, CatalogRepositories.Outbox outbox, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.attributes = attributes;
    this.productTypes = productTypes;
    this.products = products;
    this.outbox = outbox;
    this.clock = clock;
  }

  public AttributeView defineAttribute(UserContext user, DefineAttribute command) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var definition = AttributeDefinition.define(Ids.newId(), scope, command.key(), command.type(), command.labels(), command.options(),
          command.constraints(), command.flags(), clock.instant());
      attributes.insert(definition);
      outbox.append(scope, definition.pullEvents());
      return AttributeView.of(definition);
    });
  }

  public ProductTypeView createProductType(UserContext user, CreateProductType command) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var type = ProductType.create(Ids.newId(), scope, command.key(), command.name(), resolveSpecs(scope, command.attributes()));
      productTypes.insert(type);
      outbox.append(scope, type.pullEvents());
      return ProductTypeView.of(type);
    });
  }

  public ProductTypeView publishSchemaVersion(UserContext user, String productTypeKey, int version) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var type = productTypes.findByKey(scope, productTypeKey).orElseThrow(() -> HiveException.notFound("Product type not found"));
      type.publish(version, clock.instant());
      productTypes.update(type);
      outbox.append(scope, type.pullEvents());
      return ProductTypeView.of(type);
    });
  }

  public ProductView createProduct(UserContext user, CreateProduct command) {
    TenantScope scope = operatorScope(user);
    UUID actor = user.requireActor();
    return transactions.inScope(scope, () -> {
      var type = productTypes.findByKey(scope, String.valueOf(command.productType()))
          .orElseThrow(() -> io.hivecommerce.shared.DomainValidationException.of("productType", "unknown product type"));
      var product = Product.create(Ids.newId(), scope, type, command.title(), command.slug(), command.attributes(), command.variants(), actor,
          Ids::newId, clock.instant());
      products.insert(product);
      outbox.append(scope, product.pullEvents());
      return ProductView.of(product);
    });
  }

  public ProductView getProduct(UserContext user, UUID productId) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> products.findById(scope, productId).map(ProductView::of)
        .orElseThrow(() -> HiveException.notFound("Product not found")));
  }

  /** Layer 2 for operators: an authenticated actor whose Hive tenant is the deployment's tenant. */
  private TenantScope operatorScope(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    return deployment.scope();
  }

  private List<AttributeSpec> resolveSpecs(TenantScope scope, List<CatalogCommands.SpecReference> references) {
    if (references == null) return null;
    var keys = references.stream().map(CatalogCommands.SpecReference::key).toList();
    var found = attributes.findByKeys(scope, keys).stream().collect(Collectors.toMap(AttributeDefinition::key, Function.identity()));
    var violations = new Violations();
    var specs = new ArrayList<AttributeSpec>();
    for (int i = 0; i < references.size(); i++) {
      var reference = references.get(i);
      var definition = found.get(reference.key());
      if (definition == null) violations.add("attributes[" + i + "].key", "unknown attribute " + reference.key());
      else specs.add(new AttributeSpec(definition.rule(), reference.required(), reference.variantDefining()));
    }
    violations.throwIfAny();
    return specs;
  }
}
