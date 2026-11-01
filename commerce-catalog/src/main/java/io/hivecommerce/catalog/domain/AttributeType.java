package io.hivecommerce.catalog.domain;

/** Attribute types from master prompt §9. Option types carry an option list; only ENUM and COLOR may define variants. */
public enum AttributeType {
  STRING, LONG_TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, MULTI_SELECT, COLOR, MEASUREMENT, RANGE, URL, REFERENCE, JSON;

  public boolean requiresOptions() { return this == ENUM || this == MULTI_SELECT; }

  public boolean allowsOptions() { return requiresOptions() || this == COLOR; }

  public boolean canDefineVariants() { return this == ENUM || this == COLOR; }
}
