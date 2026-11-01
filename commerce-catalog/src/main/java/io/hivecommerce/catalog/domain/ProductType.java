package io.hivecommerce.catalog.domain;

import io.hivecommerce.catalog.domain.event.ProductTypeSchemaPublished;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate root for a product type and its schema versions. Invariants: a published version is immutable; at most one
 * draft exists; versions are numbered consecutively; variant-defining attributes are ENUM or COLOR; attribute keys are
 * unique within a version.
 */
public final class ProductType {
  public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,63}");
  private static final int MAX_ATTRIBUTES = 200;

  private final UUID id;
  private final TenantScope scope;
  private final String key;
  private final LocalizedText name;
  private final List<SchemaVersion> versions;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private ProductType(UUID id, TenantScope scope, String key, LocalizedText name, List<SchemaVersion> versions, long revision) {
    this.id = id;
    this.scope = scope;
    this.key = key;
    this.name = name;
    this.versions = new ArrayList<>(versions);
    this.versions.sort(Comparator.comparingInt(SchemaVersion::number));
    this.revision = revision;
  }

  /** Creates the type with draft version 1. */
  public static ProductType create(UUID id, TenantScope scope, String key, LocalizedText name, List<AttributeSpec> specs) {
    var v = new Violations();
    if (key == null || !KEY.matcher(key).matches()) v.add("key", "must match " + KEY.pattern());
    if (name == null) v.add("name", "is required");
    validateSpecs(specs, v);
    v.throwIfAny();
    return new ProductType(id, scope, key, name, List.of(new SchemaVersion(1, SchemaVersion.Status.DRAFT, specs, null)), 0);
  }

  public static ProductType restore(UUID id, TenantScope scope, String key, LocalizedText name, List<SchemaVersion> versions, long revision) {
    return new ProductType(id, scope, key, name, versions, revision);
  }

  /** Starts the next version as a draft with the given attributes (a copy-and-change of the latest version). */
  public SchemaVersion draftNextVersion(List<AttributeSpec> specs) {
    if (draft().isPresent()) throw new DomainRuleException("DRAFT_EXISTS", "Publish or replace the existing draft first");
    var v = new Violations();
    validateSpecs(specs, v);
    v.throwIfAny();
    var next = new SchemaVersion(versions.getLast().number() + 1, SchemaVersion.Status.DRAFT, specs, null);
    versions.add(next);
    return next;
  }

  public SchemaVersion publish(int number, Instant now) {
    int index = indexOf(number);
    var version = versions.get(index);
    if (version.status() == SchemaVersion.Status.PUBLISHED) throw new DomainRuleException("SCHEMA_VERSION_PUBLISHED", "Version " + number + " is already published and immutable");
    var published = version.published(now);
    versions.set(index, published);
    events.add(new ProductTypeSchemaPublished(id, key, number, now));
    return published;
  }

  public Optional<SchemaVersion> latestPublished() {
    return versions.stream().filter(s -> s.status() == SchemaVersion.Status.PUBLISHED).max(Comparator.comparingInt(SchemaVersion::number));
  }

  public Optional<SchemaVersion> draft() {
    return versions.stream().filter(s -> s.status() == SchemaVersion.Status.DRAFT).findFirst();
  }

  private int indexOf(int number) {
    for (int i = 0; i < versions.size(); i++) if (versions.get(i).number() == number) return i;
    throw new DomainRuleException("SCHEMA_VERSION_UNKNOWN", "Version " + number + " does not exist");
  }

  private static void validateSpecs(List<AttributeSpec> specs, Violations v) {
    if (specs == null) {
      v.add("attributes", "are required (may be empty)");
      return;
    }
    if (specs.size() > MAX_ATTRIBUTES) v.add("attributes", "at most " + MAX_ATTRIBUTES);
    var keys = new HashSet<String>();
    for (int i = 0; i < specs.size(); i++) {
      var spec = specs.get(i);
      if (!keys.add(spec.key())) v.add("attributes[" + i + "]", "attribute " + spec.key() + " is listed twice");
      if (spec.variantDefining() && !spec.rule().type().canDefineVariants()) {
        v.add("attributes[" + i + "]", "attribute " + spec.key() + " of type " + spec.rule().type() + " cannot define variants (ENUM or COLOR only)");
      }
    }
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public String key() { return key; }
  public LocalizedText name() { return name; }
  public List<SchemaVersion> versions() { return List.copyOf(versions); }
  public long revision() { return revision; }
}
