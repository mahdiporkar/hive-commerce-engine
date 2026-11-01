package io.hivecommerce.shared;

import java.util.ArrayList;
import java.util.List;

/** Collects violations while validating a command, then throws once with all of them. */
public final class Violations {
  private final List<DomainViolation> items = new ArrayList<>();

  public Violations add(String field, String message) {
    items.add(new DomainViolation(field, message));
    return this;
  }

  public boolean isEmpty() { return items.isEmpty(); }

  public void throwIfAny() {
    if (!items.isEmpty()) throw new DomainValidationException(items);
  }
}
