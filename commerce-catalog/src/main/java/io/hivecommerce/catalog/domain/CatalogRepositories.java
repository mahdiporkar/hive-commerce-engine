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
  }

  public interface ProductTypes {
    void insert(ProductType type);

    /** Persists new versions and status changes; fails on a stale revision. */
    void update(ProductType type);

    Optional<ProductType> findByKey(TenantScope scope, String key);

    Optional<ProductType> findById(TenantScope scope, UUID id);
  }

  public interface Products {
    void insert(Product product);

    Optional<Product> findById(TenantScope scope, UUID id);
  }

  /** Transactional outbox of this context (ADR-0002): written in the same transaction as the aggregate change. */
  public interface Outbox {
    void append(TenantScope scope, List<DomainEvent> events);
  }
}
