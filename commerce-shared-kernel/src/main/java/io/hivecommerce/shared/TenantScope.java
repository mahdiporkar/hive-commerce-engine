package io.hivecommerce.shared;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The isolation boundary every aggregate and query carries (ADR-0009). The tenant is the Hive tenant of the
 * authenticated identity; the store comes from deployment configuration. Neither is ever taken from request input.
 */
public record TenantScope(String tenantId, UUID storeId) {
  private static final Pattern TENANT = Pattern.compile("[a-z0-9][a-z0-9._-]{0,159}");

  public TenantScope {
    Objects.requireNonNull(storeId, "storeId");
    if (tenantId == null || !TENANT.matcher(tenantId).matches()) throw new IllegalArgumentException("Invalid tenant id");
  }
}
