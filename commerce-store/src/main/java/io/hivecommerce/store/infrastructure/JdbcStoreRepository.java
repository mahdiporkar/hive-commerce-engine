package io.hivecommerce.store.infrastructure;

import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.store.domain.CommerceMode;
import io.hivecommerce.store.domain.Store;
import io.hivecommerce.store.domain.StoreRepository;
import java.util.Arrays;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStoreRepository implements StoreRepository {
  private final JdbcClient jdbc;

  JdbcStoreRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertIfAbsent(Store store) {
    jdbc.sql("""
        insert into store.store(id, tenant_id, store_id, key, mode, default_currency, locales)
        values (:id, :tenant, :id, :key, :mode, :currency, :locales) on conflict (id) do nothing""")
        .param("id", store.scope().storeId()).param("tenant", store.scope().tenantId()).param("key", store.key())
        .param("mode", store.mode().name()).param("currency", store.defaultCurrency().getCurrencyCode())
        .param("locales", store.locales().toArray(String[]::new)).update();
  }

  @Override
  public Optional<Store> find(TenantScope scope) {
    return jdbc.sql("select key, mode, default_currency, locales, revision from store.store where tenant_id = :tenant and id = :store")
        .param("tenant", scope.tenantId()).param("store", scope.storeId())
        .query((rs, n) -> Store.restore(scope, rs.getString("key"), CommerceMode.valueOf(rs.getString("mode")),
            Currency.getInstance(rs.getString("default_currency")), Arrays.asList((String[]) rs.getArray("locales").getArray()), rs.getLong("revision")))
        .optional();
  }
}
