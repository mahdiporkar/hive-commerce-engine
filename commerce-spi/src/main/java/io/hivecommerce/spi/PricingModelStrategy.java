package io.hivecommerce.spi;

/** Slot for future pricing models (subscriptions, auctions; master prompt §11). Reserved and not called in SPI 1.x. */
public interface PricingModelStrategy extends CommerceExtension {
  String pricingModel();
}
