package io.hivecommerce.store.domain;

/** How a store sells (master prompt §7). The data model is marketplace-shaped in every mode. */
public enum CommerceMode {
  SINGLE_VENDOR,
  MULTI_VENDOR,
  HYBRID
}
