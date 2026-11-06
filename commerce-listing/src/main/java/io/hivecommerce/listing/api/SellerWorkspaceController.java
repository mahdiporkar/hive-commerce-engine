package io.hivecommerce.listing.api;

import io.hivecommerce.catalog.application.published.CatalogCommandsApi;
import io.hivecommerce.listing.application.ListingAdminService.ListingView;
import io.hivecommerce.listing.application.OfferService;
import io.hivecommerce.listing.application.SellerSubmissionService;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Seller workspace: submissions and offers (HYBRID; Commerce checks membership and capability). */
@RestController
@RequestMapping("/v1/seller/sellers/{sellerId}")
class SellerWorkspaceController {
  private final SellerSubmissionService submissions;
  private final OfferService offers;

  SellerWorkspaceController(SellerSubmissionService submissions, OfferService offers) {
    this.submissions = submissions;
    this.offers = offers;
  }

  @PostMapping("/submissions")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("seller-submission")
  ListingView submit(UserContext user, @PathVariable UUID sellerId, @RequestBody CatalogCommandsApi.ProductDraft body) { return submissions.submit(user, sellerId, body); }

  @GetMapping("/listings")
  @HiveOperation("seller-listings")
  List<SellerSubmissionService.SellerListingView> listings(UserContext user, @PathVariable UUID sellerId) { return submissions.listings(user, sellerId); }

  @PostMapping("/offers")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("seller-offer-create")
  OfferService.OfferView createOffer(UserContext user, @PathVariable UUID sellerId, @RequestBody OfferService.OfferCommand body) { return offers.createSellerOffer(user, sellerId, body); }

  @GetMapping("/offers")
  @HiveOperation("seller-offers")
  List<OfferService.OfferView> offers(UserContext user, @PathVariable UUID sellerId) { return offers.sellerOffers(user, sellerId); }
}
