package io.hivecommerce.store.application;

import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.store.application.published.StoreDirectory;
import io.hivecommerce.store.domain.StoreRepository;
import org.springframework.stereotype.Service;

@Service
class StoreDirectoryService implements StoreDirectory {
  private final StoreRepository stores;

  StoreDirectoryService(StoreRepository stores) {
    this.stores = stores;
  }

  @Override
  public StoreSettings settings(TenantScope scope) {
    var store = stores.find(scope).orElseThrow(() -> new IllegalStateException("Store not bootstrapped"));
    return new StoreSettings(store.key(), store.mode().name(), store.defaultCurrency().getCurrencyCode(), store.locales());
  }
}
