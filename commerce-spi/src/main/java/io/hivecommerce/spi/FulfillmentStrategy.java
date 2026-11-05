package io.hivecommerce.spi;

/**
 * Slot for future fulfillment kinds (services, bookings, rentals; master prompt §11). The core handles PHYSICAL and
 * DIGITAL itself; this contract is reserved and not called in SPI 1.x.
 */
public interface FulfillmentStrategy extends CommerceExtension {
  String productKind();
}
