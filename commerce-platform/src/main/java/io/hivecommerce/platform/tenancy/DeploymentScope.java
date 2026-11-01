package io.hivecommerce.platform.tenancy;

import io.hivecommerce.shared.TenantScope;

/**
 * The tenant and store this deployment serves (ADR-0009: one store per deployment in v1). Implemented by the Store
 * context; the platform only needs the scope, not the store's business data.
 */
public interface DeploymentScope {
  String tenantId();

  TenantScope scope();
}
