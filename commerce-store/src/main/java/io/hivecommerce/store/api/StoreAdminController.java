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
  private final org.springframework.core.env.Environment environment;

  StoreAdminController(StoreAdminService stores, org.springframework.core.env.Environment environment) {
    this.stores = stores;
    this.environment = environment;
  }

  record ModeBody(String mode) {}

  @GetMapping("/store")
  @HiveOperation("admin-store")
  StoreDirectory.StoreSettings settings(UserContext user) { return stores.settings(user); }

  /** What this deployment switched on in its configuration. Read-only: capabilities change through deployment, not through the UI. */
  @GetMapping("/store/capabilities")
  @HiveOperation("admin-store-capabilities")
  java.util.Map<String, Boolean> capabilities(UserContext user) {
    stores.settings(user);
    var out = new java.util.LinkedHashMap<String, Boolean>();
    for (String name : java.util.List.of("physical-products", "digital-products", "seller-onboarding", "commissions", "reviews")) {
      out.put(name, environment.getProperty("commerce.capabilities." + name, Boolean.class, false));
    }
    return out;
  }

  @PostMapping("/store/mode")
  @HiveOperation("admin-store-mode")
  StoreDirectory.StoreSettings changeMode(UserContext user, @RequestBody ModeBody body) { return stores.changeMode(user, body.mode()); }
}
