package io.hivecommerce.store.application.published;

import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.store.application.StoreSettings;
import io.hivecommerce.store.domain.Store;
import io.hivecommerce.store.domain.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The store this deployment serves, published to the platform and to other contexts as a {@link DeploymentScope}.
 * Bootstraps the store row at startup (before the application reports ready).
 */
@Component
@EnableConfigurationProperties(StoreSettings.class)
public class DeploymentStore implements DeploymentScope, ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DeploymentStore.class);
  private final Store configured;
  private final StoreRepository stores;
  private final TenantTransactions transactions;

  public DeploymentStore(StoreSettings settings, StoreRepository stores, TenantTransactions transactions) {
    var store = settings.store();
    if (store == null) throw new IllegalStateException("commerce.store.* configuration is required");
    this.configured = Store.bootstrap(settings.tenantId(), store.key(), store.mode(), store.defaultCurrency(), store.locales());
    this.stores = stores;
    this.transactions = transactions;
  }

  @Override
  public String tenantId() { return configured.scope().tenantId(); }

  @Override
  public TenantScope scope() { return configured.scope(); }

  @Override
  public void run(ApplicationArguments args) {
    transactions.inScope(scope(), () -> {
      stores.insertIfAbsent(configured);
      return null;
    });
    log.info("Serving store key={} tenant={} storeId={} mode={}", configured.key(), tenantId(), scope().storeId(), configured.mode());
  }
}
