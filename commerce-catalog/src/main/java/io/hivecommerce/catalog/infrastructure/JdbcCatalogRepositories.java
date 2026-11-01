package io.hivecommerce.catalog.infrastructure;

import io.hivecommerce.catalog.domain.AttributeDefinition;
import io.hivecommerce.catalog.domain.AttributeType;
import io.hivecommerce.catalog.domain.CatalogRepositories;
import io.hivecommerce.catalog.domain.Product;
import io.hivecommerce.catalog.domain.ProductType;
import io.hivecommerce.catalog.domain.SchemaVersion;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.Slug;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.CorrelationId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapters for the Catalog repository ports (ADR-0002: explicit SQL, no ORM). Every statement names the
 * {@code catalog} schema only, filters by tenant and store explicitly, and runs inside a scoped transaction so the
 * row-level-security policies apply as well.
 */
final class JdbcCatalogRepositories {
  private JdbcCatalogRepositories() {}

  /** Translates unique-constraint violations into domain conflicts with a stable code. */
  static RuntimeException conflict(DuplicateKeyException e, Map<String, String> codes) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    for (var entry : codes.entrySet()) {
      if (message.contains(entry.getKey())) return new DomainRuleException(entry.getValue(), entry.getValue().replace('_', ' ').toLowerCase());
    }
    return e;
  }

  @Repository
  static class Attributes implements CatalogRepositories.AttributeDefinitions {
    private final JdbcClient jdbc;
    private final CatalogJson json;

    Attributes(JdbcClient jdbc, CatalogJson json) {
      this.jdbc = jdbc;
      this.json = json;
    }

    @Override
    public void insert(AttributeDefinition d) {
      try {
        jdbc.sql("""
            insert into catalog.attribute_definition(id, tenant_id, store_id, key, type, labels, options, constraints, flags)
            values (:id, :tenant, :store, :key, :type, cast(:labels as jsonb), cast(:options as jsonb), cast(:constraints as jsonb), cast(:flags as jsonb))""")
            .param("id", d.id()).param("tenant", d.scope().tenantId()).param("store", d.scope().storeId()).param("key", d.key())
            .param("type", d.type().name()).param("labels", json.write(d.labels().values())).param("options", json.write(json.options(d.options())))
            .param("constraints", json.write(json.constraints(d.constraints()))).param("flags", json.write(json.flags(d.flags()))).update();
      } catch (DuplicateKeyException e) {
        throw conflict(e, Map.of("attribute_definition_key_uk", "ATTRIBUTE_KEY_TAKEN"));
      }
    }

    @Override
    public List<AttributeDefinition> findByKeys(TenantScope scope, List<String> keys) {
      if (keys.isEmpty()) return List.of();
      return jdbc.sql("""
          select id, key, type, labels, options, constraints, flags, revision from catalog.attribute_definition
          where tenant_id = :tenant and store_id = :store and key in (:keys) and archived_at is null""")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("keys", keys)
          .query((rs, n) -> AttributeDefinition.restore(rs.getObject("id", UUID.class), scope, rs.getString("key"), AttributeType.valueOf(rs.getString("type")),
              json.text(rs.getString("labels")), json.options(rs.getString("options")), json.constraints(json.map(rs.getString("constraints"))),
              json.flags(json.map(rs.getString("flags"))), rs.getLong("revision")))
          .list();
    }
  }

  @Repository
  static class ProductTypes implements CatalogRepositories.ProductTypes {
    private final JdbcClient jdbc;
    private final CatalogJson json;

    ProductTypes(JdbcClient jdbc, CatalogJson json) {
      this.jdbc = jdbc;
      this.json = json;
    }

    @Override
    public void insert(ProductType type) {
      try {
        jdbc.sql("insert into catalog.product_type(id, tenant_id, store_id, key, name) values (:id, :tenant, :store, :key, cast(:name as jsonb))")
            .param("id", type.id()).param("tenant", type.scope().tenantId()).param("store", type.scope().storeId()).param("key", type.key())
            .param("name", json.write(type.name().values())).update();
      } catch (DuplicateKeyException e) {
        throw conflict(e, Map.of("product_type_key_uk", "PRODUCT_TYPE_KEY_TAKEN"));
      }
      upsertVersions(type);
    }

    @Override
    public void update(ProductType type) {
      int updated = jdbc.sql("""
          update catalog.product_type set revision = revision + 1, updated_at = now()
          where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
          .param("id", type.id()).param("tenant", type.scope().tenantId()).param("store", type.scope().storeId()).param("revision", type.revision()).update();
      if (updated != 1) throw new OptimisticLockingFailureException("product type " + type.id() + " changed concurrently");
      upsertVersions(type);
    }

    /** New versions are inserted; a draft may change (for example to PUBLISHED); published rows are protected by a trigger. */
    private void upsertVersions(ProductType type) {
      for (var v : type.versions()) {
        jdbc.sql("""
            insert into catalog.product_type_schema_version(product_type_id, tenant_id, store_id, version, status, specs, published_at)
            values (:type, :tenant, :store, :version, :status, cast(:specs as jsonb), :published)
            on conflict (product_type_id, version) do update set status = excluded.status, specs = excluded.specs, published_at = excluded.published_at
            where catalog.product_type_schema_version.status = 'DRAFT'""")
            .param("type", type.id()).param("tenant", type.scope().tenantId()).param("store", type.scope().storeId()).param("version", v.number())
            .param("status", v.status().name()).param("specs", json.write(json.specs(v.specs())))
            .param("published", v.publishedAt() == null ? null : Timestamp.from(v.publishedAt())).update();
      }
    }

    @Override
    public Optional<ProductType> findByKey(TenantScope scope, String key) { return find(scope, "key = :value", key); }

    @Override
    public Optional<ProductType> findById(TenantScope scope, UUID id) { return find(scope, "id = :value", id); }

    private Optional<ProductType> find(TenantScope scope, String condition, Object value) {
      return jdbc.sql("select id, key, name, revision from catalog.product_type where tenant_id = :tenant and store_id = :store and " + condition)
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
          .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"), rs.getLong("revision")})
          .optional()
          .map(row -> ProductType.restore((UUID) row[0], scope, (String) row[1], json.text((String) row[2]), versions(scope, (UUID) row[0]), (Long) row[3]));
    }

    private List<SchemaVersion> versions(TenantScope scope, UUID typeId) {
      return jdbc.sql("""
          select version, status, specs, published_at from catalog.product_type_schema_version
          where product_type_id = :type and tenant_id = :tenant and store_id = :store order by version""")
          .param("type", typeId).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> new SchemaVersion(rs.getInt("version"), SchemaVersion.Status.valueOf(rs.getString("status")), json.specs(rs.getString("specs")),
              rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant()))
          .list();
    }
  }

  @Repository
  static class Products implements CatalogRepositories.Products {
    private final JdbcClient jdbc;
    private final CatalogJson json;

    Products(JdbcClient jdbc, CatalogJson json) {
      this.jdbc = jdbc;
      this.json = json;
    }

    @Override
    public void insert(Product p) {
      try {
        jdbc.sql("""
            insert into catalog.product(id, tenant_id, store_id, product_type_id, schema_version, title, slug, attributes, status, created_by)
            values (:id, :tenant, :store, :type, :version, cast(:title as jsonb), :slug, cast(:attributes as jsonb), :status, :createdBy)""")
            .param("id", p.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("type", p.productTypeId())
            .param("version", p.schemaVersion()).param("title", json.write(p.title().values())).param("slug", p.slug().value())
            .param("attributes", json.write(p.attributes())).param("status", p.status().name()).param("createdBy", p.createdBy()).update();
        for (var v : p.variants()) {
          jdbc.sql("""
              insert into catalog.product_variant(id, tenant_id, store_id, product_id, sku, attributes, variant_key)
              values (:id, :tenant, :store, :product, :sku, cast(:attributes as jsonb), :variantKey)""")
              .param("id", v.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("product", p.id())
              .param("sku", v.sku()).param("attributes", json.write(v.attributes())).param("variantKey", v.variantKey()).update();
        }
      } catch (DuplicateKeyException e) {
        throw conflict(e, Map.of("product_variant_sku_uk", "SKU_TAKEN", "product_slug_uk", "SLUG_TAKEN"));
      }
    }

    @Override
    public Optional<Product> findById(TenantScope scope, UUID id) {
      var variants = jdbc.sql("""
          select id, sku, attributes from catalog.product_variant
          where product_id = :product and tenant_id = :tenant and store_id = :store order by sku""")
          .param("product", id).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> new Product.Variant(rs.getObject("id", UUID.class), rs.getString("sku"), json.map(rs.getString("attributes"))))
          .list();
      return jdbc.sql("""
          select product_type_id, schema_version, title, slug, attributes, status, created_by, revision from catalog.product
          where id = :id and tenant_id = :tenant and store_id = :store""")
          .param("id", id).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> Product.restore(id, scope, rs.getObject("product_type_id", UUID.class), rs.getInt("schema_version"), json.text(rs.getString("title")),
              new Slug(rs.getString("slug")), json.map(rs.getString("attributes")), variants, Product.Status.valueOf(rs.getString("status")),
              rs.getObject("created_by", UUID.class), rs.getLong("revision")))
          .optional();
    }
  }

  @Repository
  static class Outbox implements CatalogRepositories.Outbox {
    private final JdbcClient jdbc;
    private final CatalogJson json;

    Outbox(JdbcClient jdbc, CatalogJson json) {
      this.jdbc = jdbc;
      this.json = json;
    }

    @Override
    public void append(TenantScope scope, List<DomainEvent> events) {
      for (DomainEvent e : events) {
        jdbc.sql("""
            insert into catalog.outbox_event(id, tenant_id, store_id, aggregate_type, aggregate_id, event_type, event_version, payload, correlation_id, occurred_at)
            values (:id, :tenant, :store, :aggregateType, :aggregateId, :type, :version, cast(:payload as jsonb), :correlation, :occurred)""")
            .param("id", Ids.newId()).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("aggregateType", e.aggregateType())
            .param("aggregateId", e.aggregateId()).param("type", e.type()).param("version", e.version()).param("payload", json.write(e.payload()))
            .param("correlation", CorrelationId.current()).param("occurred", Timestamp.from(e.occurredAt())).update();
      }
    }
  }
}
