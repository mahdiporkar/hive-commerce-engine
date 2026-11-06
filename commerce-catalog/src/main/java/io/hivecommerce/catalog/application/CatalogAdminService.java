package io.hivecommerce.catalog.application;

import io.hivecommerce.catalog.application.CatalogCommands.CreateCategory;
import io.hivecommerce.catalog.application.CatalogCommands.CreateProduct;
import io.hivecommerce.catalog.application.CatalogCommands.CreateProductType;
import io.hivecommerce.catalog.application.CatalogCommands.DefineAttribute;
import io.hivecommerce.catalog.application.CatalogCommands.MoveCategory;
import io.hivecommerce.catalog.application.CatalogViews.AttributeView;
import io.hivecommerce.catalog.application.CatalogViews.CategoryView;
import io.hivecommerce.catalog.application.CatalogViews.ProductTypeView;
import io.hivecommerce.catalog.application.CatalogViews.ProductView;
import io.hivecommerce.catalog.domain.AttributeDefinition;
import io.hivecommerce.catalog.domain.AttributeSpec;
import io.hivecommerce.catalog.domain.CatalogRepositories;
import io.hivecommerce.catalog.domain.Category;
import io.hivecommerce.catalog.domain.CategorySeo;
import io.hivecommerce.catalog.domain.Product;
import io.hivecommerce.catalog.domain.ProductKind;
import io.hivecommerce.catalog.domain.ProductMedia;
import io.hivecommerce.catalog.domain.ProductType;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.DomainValidationException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Operator use cases of the Catalog context. Layer 1 (Hive) has already checked the operator's resource action at the
 * BFF; Layer 2 here requires an authenticated actor of the deployment's tenant and scopes every read and write to the
 * deployment store (ADR-0016 §4). Each use case changes one aggregate and appends its events to the context outbox in
 * the same transaction.
 */
@Service
public class CatalogAdminService implements io.hivecommerce.catalog.application.published.CatalogCommandsApi {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final CatalogRepositories.AttributeDefinitions attributes;
  private final CatalogRepositories.ProductTypes productTypes;
  private final CatalogRepositories.Categories categories;
  private final CatalogRepositories.Products products;
  private final CatalogRepositories.Outbox outbox;
  private final CatalogRepositories.Presentation presentation;
  private final Clock clock;
  private final int maxCategoryDepth;
  private final io.hivecommerce.platform.extensions.ExtensionRegistry extensions;

  public CatalogAdminService(DeploymentScope deployment, TenantTransactions transactions, CatalogRepositories.AttributeDefinitions attributes,
      CatalogRepositories.ProductTypes productTypes, CatalogRepositories.Categories categories, CatalogRepositories.Products products,
      CatalogRepositories.Presentation presentation, CatalogRepositories.Outbox outbox, Clock clock, @Value("${commerce.catalog.max-category-depth:5}") int maxCategoryDepth,
      io.hivecommerce.platform.extensions.ExtensionRegistry extensions) {
    this.extensions = extensions;
    this.presentation = presentation;
    this.deployment = deployment;
    this.transactions = transactions;
    this.attributes = attributes;
    this.productTypes = productTypes;
    this.categories = categories;
    this.products = products;
    this.outbox = outbox;
    this.clock = clock;
    this.maxCategoryDepth = maxCategoryDepth;
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
      var kind = command.kind() == null ? ProductKind.PHYSICAL : command.kind();
      var type = ProductType.create(Ids.newId(), scope, command.key(), command.name(), kind, resolveSpecs(scope, command.attributes()));
      productTypes.insert(type);
      outbox.append(scope, type.pullEvents());
      return ProductTypeView.of(type);
    });
  }

  /** A change to a product type is a new version: a draft until it is published; existing products keep theirs. */
  public ProductTypeView draftSchemaVersion(UserContext user, String productTypeKey, List<CatalogCommands.SpecReference> specs) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var type = productTypes.findByKey(scope, productTypeKey).orElseThrow(() -> HiveException.notFound("Product type not found"));
      type.draftNextVersion(resolveSpecs(scope, specs));
      productTypes.update(type);
      return ProductTypeView.of(type);
    });
  }

  /** Moves one product to the latest published schema, explicitly and with validation; nothing migrates by itself. */
  public ProductView migrateProduct(UserContext user, UUID productId, java.util.Map<String, Object> changes, long revision) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var product = products.findById(scope, productId).orElseThrow(() -> HiveException.notFound("Product not found"));
      if (product.revision() != revision) throw new org.springframework.dao.OptimisticLockingFailureException("product " + productId + " changed concurrently");
      var type = productTypes.findById(scope, product.productTypeId()).orElseThrow();
      var migrated = product.migratedTo(type, changes, clock.instant());
      extensions.validateProduct(facts(type, migrated));
      products.updateSchema(migrated);
      outbox.append(scope, migrated.pullEvents());
      return products.findById(scope, productId).map(ProductView::of).orElseThrow();
    });
  }

  public List<ProductMedia> setProductMedia(UserContext user, UUID productId, List<ProductMedia> media) {
    TenantScope scope = operatorScope(user);
    var validated = ProductMedia.validated(media);
    return transactions.inScope(scope, () -> {
      products.findById(scope, productId).orElseThrow(() -> HiveException.notFound("Product not found"));
      presentation.saveProductMedia(scope, productId, validated);
      return validated;
    });
  }

  public CategorySeo setCategorySeo(UserContext user, UUID categoryId, CategorySeo seo) {
    TenantScope scope = operatorScope(user);
    var validated = CategorySeo.validated(seo);
    return transactions.inScope(scope, () -> {
      categories.findById(scope, categoryId).orElseThrow(() -> HiveException.notFound("Category not found"));
      presentation.saveCategorySeo(scope, categoryId, validated);
      return validated;
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

  public CategoryView createCategory(UserContext user, CreateCategory command) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      Category parent = command.parentId() == null ? null : categories.findById(scope, command.parentId())
          .orElseThrow(() -> DomainValidationException.of("parentId", "unknown category"));
      requireAttributesExist(scope, command.attributes());
      var category = Category.create(Ids.newId(), scope, parent, command.slug(), command.name(), command.attributes(), command.position(), maxCategoryDepth,
          clock.instant());
      categories.insert(category);
      outbox.append(scope, category.pullEvents());
      return CategoryView.of(category);
    });
  }

  public CategoryView moveCategory(UserContext user, UUID categoryId, MoveCategory command) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var category = existingCategory(scope, categoryId, command.revision());
      Category parent = command.parentId() == null ? null : categories.findById(scope, command.parentId())
          .orElseThrow(() -> DomainValidationException.of("parentId", "unknown category"));
      String oldPath = category.moveTo(parent, categories.subtreeHeight(scope, category), maxCategoryDepth, clock.instant());
      categories.update(category, oldPath);
      outbox.append(scope, category.pullEvents());
      return CategoryView.of(category);
    });
  }

  public CategoryView assignCategoryAttributes(UserContext user, UUID categoryId, List<String> keys, long revision) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var category = existingCategory(scope, categoryId, revision);
      requireAttributesExist(scope, keys);
      category.assignAttributes(keys, clock.instant());
      categories.update(category, null);
      outbox.append(scope, category.pullEvents());
      return CategoryView.of(category);
    });
  }

  public CategoryView archiveCategory(UserContext user, UUID categoryId, long revision) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var category = existingCategory(scope, categoryId, revision);
      category.archive(categories.hasActiveChildren(scope, categoryId), clock.instant());
      categories.update(category, null);
      outbox.append(scope, category.pullEvents());
      return CategoryView.of(category);
    });
  }

  public ProductView createProduct(UserContext user, CreateProduct command) {
    TenantScope scope = operatorScope(user);
    UUID actor = user.requireActor();
    return transactions.inScope(scope, () -> {
      var type = productTypes.findByKey(scope, String.valueOf(command.productType()))
          .orElseThrow(() -> DomainValidationException.of("productType", "unknown product type"));
      if (command.categoryId() != null) {
        var category = categories.findById(scope, command.categoryId()).orElseThrow(() -> DomainValidationException.of("categoryId", "unknown category"));
        if (category.status() != Category.Status.ACTIVE) throw DomainValidationException.of("categoryId", "category is archived");
      }
      var product = Product.create(Ids.newId(), scope, type, command.categoryId(), command.title(), command.slug(), command.attributes(), command.variants(),
          actor, Ids::newId, clock.instant());
      extensions.validateProduct(facts(type, product));
      products.insert(product);
      outbox.append(scope, product.pullEvents());
      return ProductView.of(product);
    });
  }

  @Override
  public UUID createProduct(TenantScope scope, UUID actor, ProductDraft d) {
    if (d.title() == null) throw DomainValidationException.of("title", "is required");
    var variants = d.variants() == null ? null : d.variants().stream().map(v -> new Product.VariantDraft(v.sku(), v.attributes())).toList();
    return transactions.inScope(scope, () -> {
      var type = productTypes.findByKey(scope, String.valueOf(d.productType())).orElseThrow(() -> DomainValidationException.of("productType", "unknown product type"));
      if (d.categoryId() != null) {
        var category = categories.findById(scope, d.categoryId()).orElseThrow(() -> DomainValidationException.of("categoryId", "unknown category"));
        if (category.status() != Category.Status.ACTIVE) throw DomainValidationException.of("categoryId", "category is archived");
      }
      var product = Product.create(Ids.newId(), scope, type, d.categoryId(), new io.hivecommerce.shared.LocalizedText(d.title()), d.slug(), d.attributes(), variants, actor, Ids::newId, clock.instant());
      extensions.validateProduct(facts(type, product));
      products.insert(product);
      outbox.append(scope, product.pullEvents());
      return product.id();
    });
  }

  public List<AttributeView> listAttributes(UserContext user) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> attributes.findAll(scope).stream().map(AttributeView::of).toList());
  }

  public List<ProductTypeView> listProductTypes(UserContext user) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> productTypes.findAll(scope).stream().map(ProductTypeView::of).toList());
  }

  public List<ProductView> listProducts(UserContext user) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> products.findRecent(scope, 200).stream().map(ProductView::of).toList());
  }

  public ProductView getProduct(UserContext user, UUID productId) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> products.findById(scope, productId).map(ProductView::of)
        .orElseThrow(() -> HiveException.notFound("Product not found")));
  }

  private static io.hivecommerce.spi.ProductFacts facts(ProductType type, Product product) {
    return new io.hivecommerce.spi.ProductFacts(type.key(), type.kind().name(), product.attributes(), product.variants().stream().map(Product.Variant::attributes).toList());
  }

  /** Layer 2 for operators: an authenticated actor whose Hive tenant is the deployment's tenant. */
  private TenantScope operatorScope(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    return deployment.scope();
  }

  private Category existingCategory(TenantScope scope, UUID id, long revision) {
    var category = categories.findById(scope, id).orElseThrow(() -> HiveException.notFound("Category not found"));
    if (category.revision() != revision) throw HiveException.stale();
    return category;
  }

  private void requireAttributesExist(TenantScope scope, List<String> keys) {
    if (keys == null || keys.isEmpty()) return;
    var known = attributes.findByKeys(scope, keys).stream().map(AttributeDefinition::key).collect(Collectors.toSet());
    var violations = new Violations();
    for (int i = 0; i < keys.size(); i++) if (!known.contains(keys.get(i))) violations.add("attributes[" + i + "]", "unknown attribute " + keys.get(i));
    violations.throwIfAny();
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
