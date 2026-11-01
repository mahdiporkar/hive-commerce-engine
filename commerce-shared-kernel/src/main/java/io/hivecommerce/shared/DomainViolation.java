package io.hivecommerce.shared;

/** One broken rule: {@code field} is a dotted path into the command (for example {@code attributes.carat}). */
public record DomainViolation(String field, String message) {}
