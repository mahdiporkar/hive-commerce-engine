package io.hivecommerce.spi;

/** One problem reported by an extension; {@code field} is a dotted path into the command. */
public record Issue(String field, String message) {}
