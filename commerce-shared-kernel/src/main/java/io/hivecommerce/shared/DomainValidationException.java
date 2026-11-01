package io.hivecommerce.shared;

import java.util.List;

/** A command was rejected because its content breaks invariants. Carries every violation found, not just the first. */
public class DomainValidationException extends RuntimeException {
  private final List<DomainViolation> violations;

  public DomainValidationException(List<DomainViolation> violations) {
    super(violations.size() + " validation error(s)");
    this.violations = List.copyOf(violations);
  }

  public static DomainValidationException of(String field, String message) {
    return new DomainValidationException(List.of(new DomainViolation(field, message)));
  }

  public List<DomainViolation> violations() { return violations; }
}
