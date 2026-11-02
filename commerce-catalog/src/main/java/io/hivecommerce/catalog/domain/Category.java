package io.hivecommerce.catalog.domain;

import io.hivecommerce.catalog.domain.event.CategoryChanged;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.Slug;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * A node of the store's category tree (aggregate root). The materialized {@code path} ("/rootId/childId/") makes cycle
 * checks and subtree queries cheap. Invariants: no cycles; depth ≤ the configured maximum; slug unique among siblings
 * (unique index); only active parents; archive only without active children. Assigned attribute keys are the facets
 * shown for this category and are inherited by descendants.
 */
public final class Category {
  public enum Status { ACTIVE, ARCHIVED }

  private final UUID id;
  private final TenantScope scope;
  private UUID parentId;
  private String path;
  private final Slug slug;
  private final LocalizedText name;
  private List<String> attributeKeys;
  private final int position;
  private Status status;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Category(UUID id, TenantScope scope, UUID parentId, String path, Slug slug, LocalizedText name, List<String> attributeKeys, int position,
      Status status, long revision) {
    this.id = id;
    this.scope = scope;
    this.parentId = parentId;
    this.path = path;
    this.slug = slug;
    this.name = name;
    this.attributeKeys = List.copyOf(attributeKeys);
    this.position = position;
    this.status = status;
    this.revision = revision;
  }

  public static Category create(UUID id, TenantScope scope, Category parent, String slug, LocalizedText name, List<String> attributeKeys, int position,
      int maxDepth, Instant now) {
    var v = new Violations();
    Slug parsed = null;
    try {
      parsed = new Slug(slug);
    } catch (RuntimeException e) {
      v.add("slug", "must be lowercase letters, digits and single hyphens (max 120)");
    }
    if (name == null) v.add("name", "is required");
    List<String> keys = normalizeKeys(attributeKeys, v);
    if (position < 0) v.add("position", "must not be negative");
    v.throwIfAny();
    if (parent != null) {
      if (!parent.scope.equals(scope)) throw new DomainRuleException("CATEGORY_UNKNOWN", "Unknown parent category");
      if (parent.status != Status.ACTIVE) throw new DomainRuleException("CATEGORY_PARENT_ARCHIVED", "The parent category is archived");
      if (parent.depth() + 1 > maxDepth) throw new DomainRuleException("CATEGORY_TOO_DEEP", "Categories may be at most " + maxDepth + " levels deep");
    }
    String path = (parent == null ? "/" : parent.path) + id + "/";
    var category = new Category(id, scope, parent == null ? null : parent.id, path, parsed, name, keys, position, Status.ACTIVE, 0);
    category.events.add(new CategoryChanged(id, "CREATED", now));
    return category;
  }

  public static Category restore(UUID id, TenantScope scope, UUID parentId, String path, Slug slug, LocalizedText name, List<String> attributeKeys,
      int position, Status status, long revision) {
    return new Category(id, scope, parentId, path, slug, name, attributeKeys, position, status, revision);
  }

  /**
   * Moves this category (and its subtree) under {@code newParent} (null = root). {@code subtreeHeight} is the number of
   * levels below this node (0 for a leaf), supplied by the repository.
   */
  public String moveTo(Category newParent, int subtreeHeight, int maxDepth, Instant now) {
    if (status != Status.ACTIVE) throw new DomainRuleException("CATEGORY_ARCHIVED", "Archived categories cannot be moved");
    if (newParent != null) {
      if (newParent.path.startsWith(path)) throw new DomainRuleException("CATEGORY_CYCLE", "A category cannot move below itself");
      if (newParent.status != Status.ACTIVE) throw new DomainRuleException("CATEGORY_PARENT_ARCHIVED", "The parent category is archived");
      if (newParent.depth() + 1 + subtreeHeight > maxDepth) throw new DomainRuleException("CATEGORY_TOO_DEEP", "Categories may be at most " + maxDepth + " levels deep");
    }
    String oldPath = path;
    parentId = newParent == null ? null : newParent.id;
    path = (newParent == null ? "/" : newParent.path) + id + "/";
    events.add(new CategoryChanged(id, "MOVED", now));
    return oldPath;
  }

  public void assignAttributes(List<String> keys, Instant now) {
    var v = new Violations();
    attributeKeys = normalizeKeys(keys, v);
    v.throwIfAny();
    events.add(new CategoryChanged(id, "ATTRIBUTES_ASSIGNED", now));
  }

  public void archive(boolean hasActiveChildren, Instant now) {
    if (status == Status.ARCHIVED) return;
    if (hasActiveChildren) throw new DomainRuleException("CATEGORY_HAS_CHILDREN", "Archive the child categories first");
    status = Status.ARCHIVED;
    events.add(new CategoryChanged(id, "ARCHIVED", now));
  }

  public int depth() { return (int) path.chars().filter(c -> c == '/').count() - 1; }

  private static List<String> normalizeKeys(List<String> keys, Violations v) {
    if (keys == null) return List.of();
    var unique = new LinkedHashSet<String>();
    for (int i = 0; i < keys.size(); i++) {
      String key = keys.get(i);
      if (key == null || !AttributeDefinition.KEY.matcher(key).matches()) v.add("attributes[" + i + "]", "invalid attribute key");
      else if (!unique.add(key)) v.add("attributes[" + i + "]", "is duplicated");
    }
    return List.copyOf(unique);
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID parentId() { return parentId; }
  public String path() { return path; }
  public Slug slug() { return slug; }
  public LocalizedText name() { return name; }
  public List<String> attributeKeys() { return attributeKeys; }
  public int position() { return position; }
  public Status status() { return status; }
  public long revision() { return revision; }
}
