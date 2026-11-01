package io.hivecommerce.platform.identity;

/** Headers the Hive BFF adds to proxied requests (docs/architecture/hive-integration.md). Only this package reads them. */
public final class HiveHeaders {
  public static final String USER_ID = "X-Hive-User-Id";
  public static final String TENANT_ID = "X-Hive-Tenant-Id";
  public static final String ROUTE = "X-Hive-Route";
  public static final String OPERATION = "X-Hive-Operation";

  private HiveHeaders() {}
}
