package io.hivecommerce.store.application.published;

import io.hivecommerce.shared.TenantScope;
import java.util.List;

/** Store's published in-process API (upstream of every context: published language StoreSettings). */
public interface StoreDirectory {
  record StoreSettings(String key, String mode, String currency, List<String> locales) {
    public boolean marketplace() { return !"SINGLE_VENDOR".equals(mode); }
  }

  /** Current settings of the store (mode can change at runtime; Scenario E). Must be called inside a scoped transaction. */
  StoreSettings settings(TenantScope scope);
}
