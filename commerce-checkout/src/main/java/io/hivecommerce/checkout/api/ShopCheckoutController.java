package io.hivecommerce.checkout.api;

import io.hivecommerce.checkout.application.CheckoutService;
import io.hivecommerce.checkout.application.CheckoutService.CartView;
import io.hivecommerce.checkout.application.CheckoutService.CheckoutView;
import io.hivecommerce.checkout.application.CheckoutService.CreatedCart;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopper cart and checkout (Hive HYBRID operations, ADR-0004/0013). Guests are identified by the cart token in the
 * {@code cart} query parameter; checkout requires a signed-in customer.
 */
@RestController
@RequestMapping("/v1/shop")
class ShopCheckoutController {
  private final CheckoutService checkout;

  ShopCheckoutController(CheckoutService checkout) {
    this.checkout = checkout;
  }

  record ItemBody(UUID offerId, int quantity) {}

  record CheckoutBody(String idempotencyKey) {}

  @PostMapping("/carts")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("shop-cart-create")
  CreatedCart create(UserContext user, @RequestParam(name = "cart", required = false) String token) { return checkout.createCart(user, token); }

  @GetMapping("/carts/current")
  @HiveOperation("shop-cart")
  CartView current(UserContext user, @RequestParam(name = "cart", required = false) String token) { return checkout.currentCart(user, token); }

  @PostMapping("/carts/current/items")
  @HiveOperation("shop-cart-add")
  CartView add(UserContext user, @RequestParam(name = "cart", required = false) String token, @RequestBody ItemBody body) {
    return checkout.addItem(user, token, body.offerId(), body.quantity());
  }

  @DeleteMapping("/carts/current/items/{itemId}")
  @HiveOperation("shop-cart-remove")
  CartView remove(UserContext user, @RequestParam(name = "cart", required = false) String token, @PathVariable UUID itemId) {
    return checkout.removeItem(user, token, itemId);
  }

  @PostMapping("/checkouts")
  @ResponseStatus(HttpStatus.CREATED)
  @HiveOperation("shop-checkout")
  CheckoutView checkout(UserContext user, @RequestBody CheckoutBody body) { return checkout.checkout(user, body.idempotencyKey()); }

  @GetMapping("/checkouts/{checkoutId}")
  @HiveOperation("shop-checkout-get")
  CheckoutView attempt(UserContext user, @PathVariable UUID checkoutId) { return checkout.attempt(user, checkoutId); }
}
