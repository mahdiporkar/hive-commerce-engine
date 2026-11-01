package io.hivecommerce.platform.tenancy;

import io.hivecommerce.shared.TenantScope;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in one database transaction whose row-level-security scope is set first (ADR-0009). The settings are
 * transaction-local, so a pooled connection never carries one request's scope into another. Every repository call
 * must happen inside {@link #inScope}; outside it the RLS policies match no rows (or fail on the uuid cast).
 */
@Component
public class TenantTransactions {
  private final TransactionTemplate transactions;
  private final JdbcClient jdbc;

  public TenantTransactions(TransactionTemplate transactions, JdbcClient jdbc) {
    this.transactions = transactions;
    this.jdbc = jdbc;
  }

  public <T> T inScope(TenantScope scope, Supplier<T> work) {
    return transactions.execute(status -> {
      jdbc.sql("select set_config('app.tenant_id', :tenant, true), set_config('app.store_id', :store, true)")
          .param("tenant", scope.tenantId()).param("store", scope.storeId().toString())
          .query((rs, n) -> 1).single();
      return work.get();
    });
  }
}
