package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository ports of the Catalog context, one per aggregate root. Every method takes the scope explicitly; the
 * implementations add tenant/store predicates on top of row-level security. Uniqueness conflicts surface as
 * {@link io.hivecommerce.shared.DomainRuleException} with a specific code.
 */
public final class CatalogRepositories {
  private CatalogRepositories() {}

  public interface AttributeDefinitions {
    void insert(AttributeDefinition definition);

    List<AttributeDefinition> findByKeys(TenantScope scope, List<String> keys);

    List<AttributeDefinition> findAll(TenantScope scope);
  }

  public interface ProductTypes {
    void insert(ProductType type);

    /** Persists new versions and status changes; fails on a stale revision. */
    void update(ProductType type);

    Optional<ProductType> findByKey(TenantScope scope, String key);

    Optional<ProductType> findById(TenantScope scope, UUID id);

    List<ProductType> findAll(TenantScope scope);
  }

  public interface Categories {
    void insert(Category category);

    /** Persists the category; when {@code oldPath} differs from its path, rewrites the paths of its descendants. */
    void update(Category category, String oldPath);

    Optional<Category> findById(TenantScope scope, UUID id);

    boolean hasActiveChildren(TenantScope scope, UUID id);

    /** Levels below the category (0 for a leaf). */
    int subtreeHeight(TenantScope scope, Category category);

    List<Category> findActive(TenantScope scope);

    /** The category and all its active descendants. */
    List<UUID> subtreeIds(TenantScope scope, UUID id);
  }

  public interface Products {
    void insert(Product product);

    Optional<Product> findById(TenantScope scope, UUID id);

    Optional<Product> findByVariantId(TenantScope scope, UUID variantId);

    List<Product> findRecent(TenantScope scope, int limit);
  }

  /** Transactional outbox of this context (ADR-0002): written in the same transaction as the aggregate change. */
  public interface Outbox {
    void append(TenantScope scope, List<DomainEvent> events);
  }
}
