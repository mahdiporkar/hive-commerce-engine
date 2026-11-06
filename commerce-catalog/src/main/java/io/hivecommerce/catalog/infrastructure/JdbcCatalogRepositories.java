package io.hivecommerce.catalog.infrastructure;

import io.hivecommerce.catalog.domain.AttributeDefinition;
import io.hivecommerce.catalog.domain.AttributeType;
import io.hivecommerce.catalog.domain.CatalogRepositories;
import io.hivecommerce.catalog.domain.Category;
import io.hivecommerce.catalog.domain.ProductKind;
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
      return select(scope, jdbc.sql("""
          select id, key, type, labels, options, constraints, flags, revision from catalog.attribute_definition
          where tenant_id = :tenant and store_id = :store and key in (:keys) and archived_at is null""").param("keys", keys));
    }

    @Override
    public List<AttributeDefinition> findAll(TenantScope scope) {
      return select(scope, jdbc.sql("""
          select id, key, type, labels, options, constraints, flags, revision from catalog.attribute_definition
          where tenant_id = :tenant and store_id = :store and archived_at is null order by key"""));
    }

    private List<AttributeDefinition> select(TenantScope scope, JdbcClient.StatementSpec statement) {
      return statement.param("tenant", scope.tenantId()).param("store", scope.storeId())
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
        jdbc.sql("insert into catalog.product_type(id, tenant_id, store_id, key, name, kind) values (:id, :tenant, :store, :key, cast(:name as jsonb), :kind)")
            .param("kind", type.kind().name())
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

    @Override
    public List<ProductType> findAll(TenantScope scope) {
      return jdbc.sql("select key from catalog.product_type where tenant_id = :tenant and store_id = :store and archived_at is null order by key")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).query(String.class).list().stream()
          .map(key -> findByKey(scope, key).orElseThrow()).toList();
    }

    private Optional<ProductType> find(TenantScope scope, String condition, Object value) {
      return jdbc.sql("select id, key, name, kind, revision from catalog.product_type where tenant_id = :tenant and store_id = :store and " + condition)
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
          .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"), rs.getLong("revision"), rs.getString("kind")})
          .optional()
          .map(row -> ProductType.restore((UUID) row[0], scope, (String) row[1], json.text((String) row[2]), ProductKind.valueOf((String) row[4]), versions(scope, (UUID) row[0]), (Long) row[3]));
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
  static class Categories implements CatalogRepositories.Categories {
    private final JdbcClient jdbc;
    private final CatalogJson json;

    Categories(JdbcClient jdbc, CatalogJson json) {
      this.jdbc = jdbc;
      this.json = json;
    }

    @Override
    public void insert(Category c) {
      try {
        jdbc.sql("""
            insert into catalog.category(id, tenant_id, store_id, parent_id, path, slug, name, attribute_keys, position, status)
            values (:id, :tenant, :store, :parent, :path, :slug, cast(:name as jsonb), :keys, :position, :status)""")
            .param("id", c.id()).param("tenant", c.scope().tenantId()).param("store", c.scope().storeId()).param("parent", c.parentId())
            .param("path", c.path()).param("slug", c.slug().value()).param("name", json.write(c.name().values()))
            .param("keys", c.attributeKeys().toArray(String[]::new)).param("position", c.position()).param("status", c.status().name()).update();
      } catch (DuplicateKeyException e) {
        throw conflict(e, Map.of("category_sibling_slug_uk", "CATEGORY_SLUG_TAKEN"));
      }
    }

    @Override
    public void update(Category c, String oldPath) {
      int updated;
      try {
        updated = jdbc.sql("""
            update catalog.category set parent_id = :parent, path = :path, attribute_keys = :keys, status = :status,
              revision = revision + 1, updated_at = now()
            where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
            .param("parent", c.parentId()).param("path", c.path()).param("keys", c.attributeKeys().toArray(String[]::new))
            .param("status", c.status().name()).param("id", c.id()).param("tenant", c.scope().tenantId()).param("store", c.scope().storeId())
            .param("revision", c.revision()).update();
      } catch (DuplicateKeyException e) {
        throw conflict(e, Map.of("category_sibling_slug_uk", "CATEGORY_SLUG_TAKEN"));
      }
      if (updated != 1) throw new OptimisticLockingFailureException("category " + c.id() + " changed concurrently");
      if (oldPath != null && !oldPath.equals(c.path())) {
        // Paths contain only UUIDs and slashes, so they carry no LIKE wildcards.
        jdbc.sql("""
            update catalog.category set path = :newPath || substr(path, length(:oldPath) + 1), updated_at = now()
            where tenant_id = :tenant and store_id = :store and path like :oldPrefix and id <> :id""")
            .param("newPath", c.path()).param("oldPath", oldPath).param("oldPrefix", oldPath + "%")
            .param("tenant", c.scope().tenantId()).param("store", c.scope().storeId()).param("id", c.id()).update();
      }
    }

    @Override
    public Optional<Category> findById(TenantScope scope, UUID id) {
      return select(scope, "id = :id", id).stream().findFirst();
    }

    @Override
    public boolean hasActiveChildren(TenantScope scope, UUID id) {
      return jdbc.sql("select count(*) from catalog.category where tenant_id = :tenant and store_id = :store and parent_id = :id and status = 'ACTIVE'")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("id", id).query(Long.class).single() > 0;
    }

    @Override
    public int subtreeHeight(TenantScope scope, Category c) {
      Integer deepest = jdbc.sql("""
          select max(length(path) - length(replace(path, '/', ''))) from catalog.category
          where tenant_id = :tenant and store_id = :store and path like :prefix""")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("prefix", c.path() + "%").query(Integer.class).single();
      return deepest == null ? 0 : (deepest - 1) - c.depth();
    }

    @Override
    public List<Category> findActive(TenantScope scope) {
      return select(scope, "status = 'ACTIVE'", null);
    }

    @Override
    public List<UUID> subtreeIds(TenantScope scope, UUID id) {
      return findById(scope, id).filter(root -> root.status() == Category.Status.ACTIVE).map(root -> jdbc.sql("""
          select id from catalog.category where tenant_id = :tenant and store_id = :store and status = 'ACTIVE' and path like :prefix""")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("prefix", root.path() + "%").query(UUID.class).list())
          .orElse(List.of());
    }

    private List<Category> select(TenantScope scope, String condition, UUID id) {
      var statement = jdbc.sql("select id, parent_id, path, slug, name, attribute_keys, position, status, revision from catalog.category "
              + "where tenant_id = :tenant and store_id = :store and " + condition + " order by path")
          .param("tenant", scope.tenantId()).param("store", scope.storeId());
      if (id != null) statement = statement.param("id", id);
      return statement.query((rs, n) -> Category.restore(rs.getObject("id", UUID.class), scope, rs.getObject("parent_id", UUID.class), rs.getString("path"),
              new Slug(rs.getString("slug")), json.text(rs.getString("name")), java.util.Arrays.asList((String[]) rs.getArray("attribute_keys").getArray()),
              rs.getInt("position"), Category.Status.valueOf(rs.getString("status")), rs.getLong("revision")))
          .list();
    }
  }

  /** Registers the catalog outbox with the platform dispatcher. */
  @org.springframework.context.annotation.Configuration
  static class OutboxRegistration {
    @org.springframework.context.annotation.Bean
    io.hivecommerce.platform.events.OutboxSource catalogOutbox() { return new io.hivecommerce.platform.events.OutboxSource("catalog"); }
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
            insert into catalog.product(id, tenant_id, store_id, product_type_id, category_id, schema_version, title, slug, attributes, status, created_by)
            values (:id, :tenant, :store, :type, :category, :version, cast(:title as jsonb), :slug, cast(:attributes as jsonb), :status, :createdBy)""")
            .param("id", p.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("type", p.productTypeId()).param("category", p.categoryId())
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
    public List<Product> findRecent(TenantScope scope, int limit) {
      return jdbc.sql("select id from catalog.product where tenant_id = :tenant and store_id = :store order by created_at desc limit :limit")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("limit", limit).query(UUID.class).list().stream()
          .map(id -> findById(scope, id).orElseThrow()).toList();
    }

    @Override
    public void updateSchema(Product p) {
      int updated = jdbc.sql("""
          update catalog.product set schema_version = :version, attributes = cast(:attributes as jsonb), revision = revision + 1, updated_at = now()
          where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
          .param("version", p.schemaVersion()).param("attributes", json.write(p.attributes())).param("id", p.id())
          .param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).param("revision", p.revision()).update();
      if (updated != 1) throw new OptimisticLockingFailureException("product " + p.id() + " changed concurrently");
      for (var v : p.variants()) {
        jdbc.sql("""
            update catalog.product_variant set attributes = cast(:attributes as jsonb)
            where id = :id and tenant_id = :tenant and store_id = :store""")
            .param("attributes", json.write(v.attributes())).param("id", v.id()).param("tenant", p.scope().tenantId()).param("store", p.scope().storeId()).update();
      }
    }

    @Override
    public Optional<Product> findByVariantId(TenantScope scope, UUID variantId) {
      return jdbc.sql("select product_id from catalog.product_variant where id = :id and tenant_id = :tenant and store_id = :store")
          .param("id", variantId).param("tenant", scope.tenantId()).param("store", scope.storeId()).query(UUID.class).optional()
          .flatMap(productId -> findById(scope, productId));
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
          select product_type_id, category_id, schema_version, title, slug, attributes, status, created_by, revision from catalog.product
          where id = :id and tenant_id = :tenant and store_id = :store""")
          .param("id", id).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> Product.restore(id, scope, rs.getObject("product_type_id", UUID.class), rs.getObject("category_id", UUID.class), rs.getInt("schema_version"), json.text(rs.getString("title")),
              new Slug(rs.getString("slug")), json.map(rs.getString("attributes")), variants, Product.Status.valueOf(rs.getString("status")),
              rs.getObject("created_by", UUID.class), rs.getLong("revision")))
          .optional();
    }
  }

  @Repository
  static class Presentation implements CatalogRepositories.Presentation {
    private static final com.fasterxml.jackson.core.type.TypeReference<List<io.hivecommerce.catalog.domain.ProductMedia>> MEDIA = new com.fasterxml.jackson.core.type.TypeReference<>() {};
    private final JdbcClient jdbc;
    private final CatalogJson json;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    Presentation(JdbcClient jdbc, CatalogJson json, com.fasterxml.jackson.databind.ObjectMapper mapper) {
      this.jdbc = jdbc;
      this.json = json;
      this.mapper = mapper;
    }

    @Override
    public List<io.hivecommerce.catalog.domain.ProductMedia> productMedia(TenantScope scope, UUID productId) {
      return jdbc.sql("select media from catalog.product where id = :id and tenant_id = :tenant and store_id = :store")
          .param("id", productId).param("tenant", scope.tenantId()).param("store", scope.storeId()).query(String.class).optional().map(text -> {
            try {
              return mapper.readValue(text, MEDIA);
            } catch (java.io.IOException e) {
              throw new IllegalStateException("Unreadable product media", e);
            }
          }).orElse(List.of());
    }

    @Override
    public java.util.Map<UUID, List<io.hivecommerce.catalog.domain.ProductMedia>> productMedia(TenantScope scope, java.util.Collection<UUID> productIds) {
      if (productIds.isEmpty()) return java.util.Map.of();
      var out = new java.util.HashMap<UUID, List<io.hivecommerce.catalog.domain.ProductMedia>>();
      jdbc.sql("select id, media from catalog.product where id in (:ids) and tenant_id = :tenant and store_id = :store and media <> '[]'::jsonb")
          .param("ids", List.copyOf(productIds)).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> {
            try {
              out.put(rs.getObject("id", UUID.class), mapper.readValue(rs.getString("media"), MEDIA));
            } catch (java.io.IOException e) {
              throw new IllegalStateException("Unreadable product media", e);
            }
            return null;
          }).list();
      return out;
    }

    @Override
    public void saveProductMedia(TenantScope scope, UUID productId, List<io.hivecommerce.catalog.domain.ProductMedia> media) {
      jdbc.sql("update catalog.product set media = cast(:media as jsonb), updated_at = now() where id = :id and tenant_id = :tenant and store_id = :store")
          .param("media", json.write(media)).param("id", productId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
    }

    @Override
    public io.hivecommerce.catalog.domain.CategorySeo categorySeo(TenantScope scope, UUID categoryId) {
      return jdbc.sql("select seo from catalog.category where id = :id and tenant_id = :tenant and store_id = :store")
          .param("id", categoryId).param("tenant", scope.tenantId()).param("store", scope.storeId()).query(String.class).optional().map(text -> {
            try {
              return mapper.readValue(text, io.hivecommerce.catalog.domain.CategorySeo.class);
            } catch (java.io.IOException e) {
              throw new IllegalStateException("Unreadable category metadata", e);
            }
          }).orElse(new io.hivecommerce.catalog.domain.CategorySeo(Map.of(), Map.of()));
    }

    @Override
    public void saveCategorySeo(TenantScope scope, UUID categoryId, io.hivecommerce.catalog.domain.CategorySeo seo) {
      jdbc.sql("update catalog.category set seo = cast(:seo as jsonb), updated_at = now() where id = :id and tenant_id = :tenant and store_id = :store")
          .param("seo", json.write(seo)).param("id", categoryId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
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
