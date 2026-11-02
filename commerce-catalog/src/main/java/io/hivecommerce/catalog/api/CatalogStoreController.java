package io.hivecommerce.catalog.api;

import io.hivecommerce.catalog.application.CatalogStorefrontService;
import io.hivecommerce.catalog.application.published.CatalogQueries.CategoryNode;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public storefront catalog API (Hive PUBLIC operations under {@code /v1/store}). */
@RestController
@RequestMapping("/v1/store")
class CatalogStoreController {
  private final CatalogStorefrontService storefront;

  CatalogStoreController(CatalogStorefrontService storefront) {
    this.storefront = storefront;
  }

  @GetMapping("/categories")
  @HiveOperation("store-categories")
  List<CategoryNode> categories(UserContext user) {
    return storefront.categories(user);
  }
}
