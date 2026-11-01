package io.hivecommerce.store.domain;

import io.hivecommerce.shared.TenantScope;
import java.util.Optional;

public interface StoreRepository {
  /** Inserts the store if it does not exist yet; an existing store is left untouched. */
  void insertIfAbsent(Store store);

  Optional<Store> find(TenantScope scope);
}
