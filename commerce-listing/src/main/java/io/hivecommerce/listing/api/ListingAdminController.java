package io.hivecommerce.listing.api;

import io.hivecommerce.listing.application.ListingAdminService;
import io.hivecommerce.listing.application.ListingAdminService.ListingView;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator listing API (Hive AUTHENTICATED operations, commerce.api.catalog:publish / commerce.api.moderation:decide). */
@RestController
@RequestMapping("/v1/admin")
class ListingAdminController {
  private final ListingAdminService listings;

  ListingAdminController(ListingAdminService listings) {
    this.listings = listings;
  }

  record RejectBody(String reason) {}

  @PostMapping("/products/{productId}/publish")
  @HiveOperation("admin-product-publish")
  ListingView publish(UserContext user, @PathVariable UUID productId) {
    return listings.publishProduct(user, productId);
  }

  @PostMapping("/listings/{listingId}/unpublish")
  @HiveOperation("admin-listing-unpublish")
  ListingView unpublish(UserContext user, @PathVariable UUID listingId) {
    return listings.unpublish(user, listingId);
  }

  @PostMapping("/listings/{listingId}/approve")
  @HiveOperation("admin-listing-approve")
  ListingView approve(UserContext user, @PathVariable UUID listingId) {
    return listings.approve(user, listingId);
  }

  @PostMapping("/listings/{listingId}/reject")
  @HiveOperation("admin-listing-reject")
  ListingView reject(UserContext user, @PathVariable UUID listingId, @RequestBody RejectBody body) {
    return listings.reject(user, listingId, body.reason());
  }
}
