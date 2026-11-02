package io.hivecommerce.listing.api;

import io.hivecommerce.listing.application.OfferService;
import io.hivecommerce.listing.application.OfferService.OfferCommand;
import io.hivecommerce.listing.application.OfferService.OfferView;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator offers for the store's own (internal) seller. */
@RestController
@RequestMapping("/v1/admin")
class OfferAdminController {
  private final OfferService offers;

  OfferAdminController(OfferService offers) {
    this.offers = offers;
  }

  @PostMapping("/offers")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("admin-offer-create")
  OfferView create(UserContext user, @RequestBody OfferCommand body) {
    return offers.createStoreOffer(user, body);
  }
}
