package io.hivecommerce.listing.api;

import io.hivecommerce.listing.application.ListingProjection.Detail;
import io.hivecommerce.listing.application.ListingProjection.Page;
import io.hivecommerce.listing.application.ListingStorefrontService;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public storefront product browsing (Hive PUBLIC operations). */
@RestController
@RequestMapping("/v1/store")
class ListingStoreController {
  private final ListingStorefrontService storefront;

  ListingStoreController(ListingStorefrontService storefront) {
    this.storefront = storefront;
  }

  @GetMapping("/products")
  @HiveOperation("store-products-search")
  Page search(UserContext user, @RequestParam Map<String, String> params) {
    return storefront.search(user, params);
  }

  @GetMapping("/products/{slug}")
  @HiveOperation("store-product-detail")
  Detail detail(UserContext user, @PathVariable String slug) {
    return storefront.bySlug(user, slug);
  }
}
