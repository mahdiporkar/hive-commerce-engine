package io.hivecommerce.store.api;

import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import io.hivecommerce.store.application.StoreAdminService;
import io.hivecommerce.store.application.published.StoreDirectory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator store settings (AUTHENTICATED: commerce.api.stores:view / :configure). */
@RestController
@RequestMapping("/v1/admin")
class StoreAdminController {
  private final StoreAdminService stores;

  StoreAdminController(StoreAdminService stores) {
    this.stores = stores;
  }

  record ModeBody(String mode) {}

  @GetMapping("/store")
  @HiveOperation("admin-store")
  StoreDirectory.StoreSettings settings(UserContext user) { return stores.settings(user); }

  @PostMapping("/store/mode")
  @HiveOperation("admin-store-mode")
  StoreDirectory.StoreSettings changeMode(UserContext user, @RequestBody ModeBody body) { return stores.changeMode(user, body.mode()); }
}
