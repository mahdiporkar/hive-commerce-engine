package io.hivecommerce.inventory.api;

import io.hivecommerce.inventory.application.InventoryService;
import io.hivecommerce.inventory.application.InventoryService.StockView;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin")
class InventoryAdminController {
  private final InventoryService inventory;

  InventoryAdminController(InventoryService inventory) {
    this.inventory = inventory;
  }

  record StockBody(int onHand, long revision) {}

  @PostMapping("/offers/{offerId}/stock")
  @HiveOperation("admin-offer-stock")
  StockView adjust(UserContext user, @PathVariable UUID offerId, @RequestBody StockBody body) {
    return inventory.adjust(user, offerId, body.onHand(), body.revision());
  }
}
