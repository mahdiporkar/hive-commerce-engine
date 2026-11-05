package io.hivecommerce.ordering.api;

import io.hivecommerce.ordering.application.OrderingService;
import io.hivecommerce.ordering.application.OrderingService.OrderView;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

final class OrderingControllers {
  private OrderingControllers() {}

  /** Customer order history (HYBRID: Commerce requires a signed-in customer and returns only their orders). */
  @RestController
  @RequestMapping("/v1/shop")
  static class ShopOrders {
    private final OrderingService ordering;

    ShopOrders(OrderingService ordering) {
      this.ordering = ordering;
    }

    @GetMapping("/orders")
    @HiveOperation("shop-orders")
    List<OrderView> myOrders(UserContext user) { return ordering.myOrders(user); }

    @GetMapping("/orders/{orderId}")
    @HiveOperation("shop-order")
    OrderView myOrder(UserContext user, @PathVariable UUID orderId) { return ordering.myOrder(user, orderId); }
  }

  /** Seller workspace: the seller's part of orders only (HYBRID; orders.view on that seller). */
  @RestController
  @RequestMapping("/v1/seller/sellers/{sellerId}")
  static class SellerOrders {
    private final OrderingService ordering;

    SellerOrders(OrderingService ordering) {
      this.ordering = ordering;
    }

    @GetMapping("/orders")
    @HiveOperation("seller-orders")
    List<OrderingService.SellerOrderPart> orders(UserContext user, @PathVariable UUID sellerId) { return ordering.sellerOrders(user, sellerId); }
  }

  /** Operator order views (AUTHENTICATED, commerce.api.orders:view). */
  @RestController
  @RequestMapping("/v1/admin")
  static class AdminOrders {
    private final OrderingService ordering;

    AdminOrders(OrderingService ordering) {
      this.ordering = ordering;
    }

    @GetMapping("/orders")
    @HiveOperation("admin-orders")
    List<OrderView> orders(UserContext user) { return ordering.storeOrders(user); }

    @GetMapping("/orders/{orderId}")
    @HiveOperation("admin-order")
    OrderView order(UserContext user, @PathVariable UUID orderId) { return ordering.storeOrder(user, orderId); }
  }
}
