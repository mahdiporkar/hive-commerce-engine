package io.hivecommerce.catalog.domain;

import io.hivecommerce.catalog.domain.event.ProductCreated;
import io.hivecommerce.catalog.domain.event.ProductSchemaMigrated;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.Slug;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Aggregate root: the reusable description of an item and its purchasable variants. Invariants: attribute values are
 * valid against a PUBLISHED schema version; at least one variant; variant-defining combinations unique; SKUs unique
 * within the product (and within the store, enforced by a unique index); a type without variant-defining attributes
 * has exactly one implicit variant. Price, stock and sellers are not part of this aggregate.
 */
public final class Product {
  public static final Pattern SKU = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final int MAX_VARIANTS = 500;

  public enum Status { DRAFT, ARCHIVED }

  public record Variant(UUID id, String sku, Map<String, Object> attributes) {
    public Variant {
      attributes = Map.copyOf(attributes);
    }

    /** Canonical identity of the variant-defining values, used for the uniqueness invariant. */
    public String variantKey() {
      return attributes.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";"));
    }
  }

  public record VariantDraft(String sku, Map<String, Object> attributes) {}

  private final UUID id;
  private final TenantScope scope;
  private final UUID productTypeId;
  private final UUID categoryId;
  private final int schemaVersion;
  private final LocalizedText title;
  private final Slug slug;
  private final Map<String, Object> attributes;
  private final List<Variant> variants;
  private final Status status;
  private final UUID createdBy;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Product(UUID id, TenantScope scope, UUID productTypeId, UUID categoryId, int schemaVersion, LocalizedText title, Slug slug, Map<String, Object> attributes,
      List<Variant> variants, Status status, UUID createdBy, long revision) {
    this.id = id;
    this.scope = scope;
    this.productTypeId = productTypeId;
    this.categoryId = categoryId;
    this.schemaVersion = schemaVersion;
    this.title = title;
    this.slug = slug;
    this.attributes = Map.copyOf(attributes);
    this.variants = List.copyOf(variants);
    this.status = status;
    this.createdBy = createdBy;
    this.revision = revision;
  }

  public static Product create(UUID id, TenantScope scope, ProductType type, UUID categoryId, LocalizedText title, String slug, Map<String, Object> attributes,
      List<VariantDraft> variantDrafts, UUID createdBy, Supplier<UUID> ids, Instant now) {
    if (!type.scope().equals(scope)) throw new DomainRuleException("PRODUCT_TYPE_UNKNOWN", "Unknown product type");
    SchemaVersion schema = type.latestPublished()
        .orElseThrow(() -> new DomainRuleException("SCHEMA_NOT_PUBLISHED", "Product type " + type.key() + " has no published schema version"));
    var v = new Violations();
    if (title == null) v.add("title", "is required");
    Slug parsedSlug = null;
    try {
      parsedSlug = new Slug(slug);
    } catch (RuntimeException e) {
      v.add("slug", "must be lowercase letters, digits and single hyphens (max 120)");
    }
    var normalized = schema.normalizeProductAttributes(attributes, v);
    var variants = variants(schema, variantDrafts, ids, v);
    v.throwIfAny();
    var product = new Product(id, scope, type.id(), categoryId, schema.number(), title, parsedSlug, normalized, variants, Status.DRAFT, createdBy, 0);
    product.events.add(new ProductCreated(id, type.key(), schema.number(), variants.size(), now));
    return product;
  }

  private static List<Variant> variants(SchemaVersion schema, List<VariantDraft> drafts, Supplier<UUID> ids, Violations v) {
    drafts = drafts == null ? List.of() : drafts;
    if (drafts.isEmpty()) {
      v.add("variants", "at least one variant (with its SKU) is required");
      return List.of();
    }
    if (drafts.size() > MAX_VARIANTS) v.add("variants", "at most " + MAX_VARIANTS);
    if (!schema.hasVariantDefiningAttributes() && drafts.size() > 1) v.add("variants", "this product type has no variant-defining attributes, so exactly one variant is allowed");
    var skus = new HashSet<String>();
    var keys = new HashSet<String>();
    var result = new ArrayList<Variant>();
    for (int i = 0; i < drafts.size(); i++) {
      var draft = drafts.get(i);
      String field = "variants[" + i + "]";
      if (draft == null) {
        v.add(field, "must not be null");
        continue;
      }
      if (draft.sku() == null || !SKU.matcher(draft.sku()).matches()) v.add(field + ".sku", "must match " + SKU.pattern());
      else if (!skus.add(draft.sku())) v.add(field + ".sku", "is duplicated within the product");
      var values = schema.normalizeVariantAttributes(draft.attributes(), field + ".attributes", v);
      var variant = new Variant(ids.get(), draft.sku(), values);
      if (!keys.add(variant.variantKey())) v.add(field + ".attributes", "duplicates the combination of another variant");
      result.add(variant);
    }
    return result;
  }

  public static Product restore(UUID id, TenantScope scope, UUID productTypeId, UUID categoryId, int schemaVersion, LocalizedText title, Slug slug, Map<String, Object> attributes,
      List<Variant> variants, Status status, UUID createdBy, long revision) {
    return new Product(id, scope, productTypeId, categoryId, schemaVersion, title, slug, attributes, variants, status, createdBy, revision);
  }

  /**
   * The same product validated against the type's latest published schema (master prompt §9: never silently). Product
   * values may be supplied for attributes the new version requires; variants keep their ids and must still be valid.
   */
  public Product migratedTo(ProductType type, Map<String, Object> changes, Instant now) {
    SchemaVersion schema = type.latestPublished().orElseThrow(() -> new DomainRuleException("SCHEMA_NOT_PUBLISHED", "Product type " + type.key() + " has no published schema version"));
    if (schema.number() == schemaVersion) throw new DomainRuleException("SCHEMA_CURRENT", "This product already uses version " + schemaVersion);
    var v = new Violations();
    var merged = new java.util.HashMap<>(attributes);
    if (changes != null) merged.putAll(changes);
    var normalized = schema.normalizeProductAttributes(merged, v);
    var migrated = new ArrayList<Variant>();
    for (int i = 0; i < variants.size(); i++) {
      var variant = variants.get(i);
      migrated.add(new Variant(variant.id(), variant.sku(), schema.normalizeVariantAttributes(variant.attributes(), "variants[" + i + "].attributes", v)));
    }
    v.throwIfAny();
    var product = new Product(id, scope, productTypeId, categoryId, schema.number(), title, slug, normalized, migrated, status, createdBy, revision);
    product.events.add(new ProductSchemaMigrated(id, type.key(), schemaVersion, schema.number(), now));
    return product;
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID productTypeId() { return productTypeId; }
  public UUID categoryId() { return categoryId; }
  public int schemaVersion() { return schemaVersion; }
  public LocalizedText title() { return title; }
  public Slug slug() { return slug; }
  public Map<String, Object> attributes() { return attributes; }
  public List<Variant> variants() { return variants; }
  public Status status() { return status; }
  public UUID createdBy() { return createdBy; }
  public long revision() { return revision; }
}
