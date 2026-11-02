package io.hivecommerce.fulfillment.api;

import io.hivecommerce.fulfillment.application.FulfillmentService;
import io.hivecommerce.fulfillment.application.FulfillmentService.FulfillmentView;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator fulfillment (AUTHENTICATED: commerce.api.orders:view / :fulfill). */
@RestController
@RequestMapping("/v1/admin")
class FulfillmentAdminController {
  private final FulfillmentService fulfillment;

  FulfillmentAdminController(FulfillmentService fulfillment) {
    this.fulfillment = fulfillment;
  }

  record ShipBody(String carrier, String trackingCode) {}

  @GetMapping("/orders/{orderId}/fulfillments")
  @HiveOperation("admin-order-fulfillments")
  List<FulfillmentView> ofOrder(UserContext user, @PathVariable UUID orderId) { return fulfillment.ofOrder(user, orderId); }

  @PostMapping("/fulfillments/{fulfillmentId}/ship")
  @HiveOperation("admin-fulfillment-ship")
  FulfillmentView ship(UserContext user, @PathVariable UUID fulfillmentId, @RequestBody ShipBody body) {
    return fulfillment.ship(user, fulfillmentId, body.carrier(), body.trackingCode());
  }

  @PostMapping("/fulfillments/{fulfillmentId}/deliver")
  @HiveOperation("admin-fulfillment-deliver")
  FulfillmentView deliver(UserContext user, @PathVariable UUID fulfillmentId) { return fulfillment.deliver(user, fulfillmentId); }
}
