package io.hivecommerce.catalog.domain;

/**
 * How products of a type are fulfilled (master prompt §11). Behavior per kind lives in strategies (fulfillment SPI), not
 * here; future kinds (services, subscriptions, bookings) are added as new values with their own strategies.
 */
public enum ProductKind {
  PHYSICAL,
  DIGITAL
}
