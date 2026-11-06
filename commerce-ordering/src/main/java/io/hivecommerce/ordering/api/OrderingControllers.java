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
  record ReviewBody(int rating, String body) {}

  /** Reviews: public reads, verified-purchase writes (HYBRID, decided by Commerce), operator moderation. */
  @RestController
  @RequestMapping("/v1")
  static class Reviews {
    private final io.hivecommerce.ordering.application.ReviewService reviews;

    Reviews(io.hivecommerce.ordering.application.ReviewService reviews) {
      this.reviews = reviews;
    }

    @GetMapping("/store/products/{productId}/reviews")
    @HiveOperation("store-product-reviews")
    io.hivecommerce.ordering.application.ReviewService.ProductReviews forProduct(UserContext user, @PathVariable UUID productId) { return reviews.forProduct(productId); }

    @org.springframework.web.bind.annotation.PostMapping("/shop/products/{productId}/reviews")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @HiveOperation("shop-review-write")
    io.hivecommerce.ordering.application.ReviewService.ReviewView write(UserContext user, @PathVariable UUID productId, @org.springframework.web.bind.annotation.RequestBody ReviewBody body) {
      return reviews.write(user, productId, body.rating(), body.body());
    }

    @GetMapping("/admin/reviews")
    @HiveOperation("admin-reviews")
    List<io.hivecommerce.ordering.application.ReviewService.ReviewView> recent(UserContext user) { return reviews.recent(user); }

    @org.springframework.web.bind.annotation.PostMapping("/admin/reviews/{reviewId}/hide")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @HiveOperation("admin-review-hide")
    void hide(UserContext user, @PathVariable UUID reviewId) { reviews.hide(user, reviewId); }
  }

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

    @GetMapping("/customers")
    @HiveOperation("admin-customers")
    List<io.hivecommerce.ordering.domain.OrderRepository.CustomerSummary> customers(UserContext user) { return ordering.customers(user); }

    @GetMapping("/orders")
    @HiveOperation("admin-orders")
    List<OrderView> orders(UserContext user) { return ordering.storeOrders(user); }

    @GetMapping("/orders/{orderId}")
    @HiveOperation("admin-order")
    OrderView order(UserContext user, @PathVariable UUID orderId) { return ordering.storeOrder(user, orderId); }
  }
}
