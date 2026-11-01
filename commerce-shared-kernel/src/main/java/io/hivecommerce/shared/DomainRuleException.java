package io.hivecommerce.shared;

/** A command is valid in itself but not allowed in the aggregate's current state (for example publishing twice). */
public class DomainRuleException extends RuntimeException {
  private final String code;

  public DomainRuleException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() { return code; }
}
