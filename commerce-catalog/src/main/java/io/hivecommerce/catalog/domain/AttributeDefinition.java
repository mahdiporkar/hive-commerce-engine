package io.hivecommerce.catalog.domain;

import io.hivecommerce.catalog.domain.event.AttributeDefined;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A reusable attribute in the store's attribute library (aggregate root). Invariants: key unique per store (checked
 * by the repository), option types carry valid unique option codes, constraints fit the type.
 */
public final class AttributeDefinition {
  public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final Pattern OPTION = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

  private final UUID id;
  private final TenantScope scope;
  private final String key;
  private final AttributeType type;
  private final LocalizedText labels;
  private final List<AttributeOption> options;
  private final AttributeConstraints constraints;
  private final AttributeFlags flags;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private AttributeDefinition(UUID id, TenantScope scope, String key, AttributeType type, LocalizedText labels, List<AttributeOption> options,
      AttributeConstraints constraints, AttributeFlags flags, long revision) {
    this.id = id;
    this.scope = scope;
    this.key = key;
    this.type = type;
    this.labels = labels;
    this.options = List.copyOf(options);
    this.constraints = constraints;
    this.flags = flags;
    this.revision = revision;
  }

  public static AttributeDefinition define(UUID id, TenantScope scope, String key, AttributeType type, LocalizedText labels,
      List<AttributeOption> options, AttributeConstraints constraints, AttributeFlags flags, Instant now) {
    var v = new Violations();
    options = options == null ? List.of() : options;
    constraints = constraints == null ? AttributeConstraints.NONE : constraints;
    if (key == null || !KEY.matcher(key).matches()) v.add("key", "must match " + KEY.pattern());
    if (type == null) v.add("type", "is required");
    if (labels == null) v.add("labels", "at least one label is required");
    if (type != null) {
      if (type.requiresOptions() && options.isEmpty()) v.add("options", "are required for " + type);
      if (!type.allowsOptions() && !options.isEmpty()) v.add("options", "are not allowed for " + type);
      validateConstraints(type, constraints, v);
    }
    var codes = new HashSet<String>();
    for (int i = 0; i < options.size(); i++) {
      var option = options.get(i);
      if (option == null || option.code() == null || !OPTION.matcher(option.code()).matches()) v.add("options[" + i + "].code", "must match " + OPTION.pattern());
      else if (!codes.add(option.code())) v.add("options[" + i + "].code", "is duplicated");
      if (option != null && option.labels() == null) v.add("options[" + i + "].labels", "are required");
    }
    if (options.size() > 500) v.add("options", "at most 500 options");
    v.throwIfAny();
    var definition = new AttributeDefinition(id, scope, key, type, labels, options, constraints, flags == null ? AttributeFlags.DEFAULT : flags, 0);
    definition.events.add(new AttributeDefined(id, key, type.name(), now));
    return definition;
  }

  private static void validateConstraints(AttributeType type, AttributeConstraints c, Violations v) {
    boolean text = type == AttributeType.STRING || type == AttributeType.LONG_TEXT;
    boolean numeric = type == AttributeType.INTEGER || type == AttributeType.DECIMAL || type == AttributeType.MEASUREMENT || type == AttributeType.RANGE;
    if (!text && (c.minLength() != null || c.maxLength() != null)) v.add("constraints", "length limits apply only to text types");
    if (c.minLength() != null && c.minLength() < 0) v.add("constraints.minLength", "must not be negative");
    if (c.maxLength() != null && c.maxLength() < 1) v.add("constraints.maxLength", "must be positive");
    if (c.minLength() != null && c.maxLength() != null && c.minLength() > c.maxLength()) v.add("constraints", "minLength exceeds maxLength");
    if (!numeric && (c.min() != null || c.max() != null)) v.add("constraints", "min/max apply only to numeric types");
    if (c.min() != null && c.max() != null && c.min().compareTo(c.max()) > 0) v.add("constraints", "min exceeds max");
    if (type == AttributeType.MEASUREMENT && (c.units() == null || c.units().isEmpty())) v.add("constraints.units", "are required for MEASUREMENT");
    if (type != AttributeType.MEASUREMENT && c.units() != null) v.add("constraints.units", "apply only to MEASUREMENT");
    if (type != AttributeType.REFERENCE && c.referenceType() != null) v.add("constraints.referenceType", "applies only to REFERENCE");
  }

  public static AttributeDefinition restore(UUID id, TenantScope scope, String key, AttributeType type, LocalizedText labels, List<AttributeOption> options,
      AttributeConstraints constraints, AttributeFlags flags, long revision) {
    return new AttributeDefinition(id, scope, key, type, labels, options, constraints, flags, revision);
  }

  /** The immutable snapshot copied into product-type schema versions. */
  public AttributeRule rule() {
    return new AttributeRule(key, type, options.stream().map(AttributeOption::code).toList(), constraints);
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public String key() { return key; }
  public AttributeType type() { return type; }
  public LocalizedText labels() { return labels; }
  public List<AttributeOption> options() { return options; }
  public AttributeConstraints constraints() { return constraints; }
  public AttributeFlags flags() { return flags; }
  public long revision() { return revision; }
}
