package io.hivecommerce.checkout.application;

import io.hivecommerce.checkout.domain.Cart;
import io.hivecommerce.checkout.domain.CheckoutAttempt;
import io.hivecommerce.checkout.domain.CheckoutRepositories;
import io.hivecommerce.inventory.application.published.InventoryApi;
import io.hivecommerce.listing.application.published.ListingQueries;
import io.hivecommerce.listing.application.published.ListingQueries.OfferSnapshot;
import io.hivecommerce.ordering.application.published.OrderingApi;
import io.hivecommerce.payment.application.published.PaymentApi;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.pricing.application.published.PricingApi;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Carts (ADR-0013) and the CheckoutProcess saga (docs/domain/aggregates.md). Every saga step is its own transaction and
 * is recorded on the CheckoutAttempt, so a retry with the same idempotency key resumes or returns the earlier result.
 * Prices are always recomputed on the server (master prompt §12 rule 1). The unique-item guarantee rests on Inventory.
 */
@Service
public class CheckoutService {
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");
  private static final SecureRandom RANDOM = new SecureRandom();
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final CheckoutRepositories.Carts carts;
  private final CheckoutRepositories.Attempts attempts;
  private final ListingQueries listing;
  private final PricingApi pricing;
  private final InventoryApi inventory;
  private final OrderingApi ordering;
  private final PaymentApi payments;
  private final Clock clock;
  private final Duration guestCartTtl;
  private final Duration reservationTtl;

  public CheckoutService(DeploymentScope deployment, TenantTransactions transactions, CheckoutRepositories.Carts carts, CheckoutRepositories.Attempts attempts,
      ListingQueries listing, PricingApi pricing, InventoryApi inventory, OrderingApi ordering, PaymentApi payments, Clock clock,
      @Value("${commerce.checkout.guest-cart-ttl:P7D}") Duration guestCartTtl, @Value("${commerce.inventory.reservation-ttl:PT15M}") Duration reservationTtl) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.carts = carts;
    this.attempts = attempts;
    this.listing = listing;
    this.pricing = pricing;
    this.inventory = inventory;
    this.ordering = ordering;
    this.payments = payments;
    this.clock = clock;
    this.guestCartTtl = guestCartTtl;
    this.reservationTtl = reservationTtl;
  }

  // ---------------------------------------------------------------- carts

  public record CartLine(UUID itemId, UUID offerId, UUID productId, String productSlug, Map<String, String> title, String sku, Map<String, Object> variantAttributes,
      BigDecimal unitPrice, String currency, int quantity, boolean uniqueItem, boolean purchasable) {}

  public record CartTotals(BigDecimal subtotal, BigDecimal tax, BigDecimal shipping, BigDecimal total, String currency) {}

  public record CartView(UUID cartId, String status, List<CartLine> lines, CartTotals totals) {}

  public record CreatedCart(String cartToken, CartView cart) {}

  /** Anonymous callers get a new guest cart and its token (shown once); signed-in customers get their cart. */
  public CreatedCart createCart(UserContext user, String guestToken) {
    TenantScope scope = deployment.scope();
    if (user.authenticated()) return new CreatedCart(null, transactions.inScope(scope, () -> view(scope, customerCart(scope, user.actorId(), guestToken))));
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
    var cart = Cart.forGuest(Ids.newId(), scope, hash(token), clock.instant().plus(guestCartTtl));
    return new CreatedCart(token, transactions.inScope(scope, () -> {
      carts.insert(cart);
      return view(scope, cart);
    }));
  }

  public CartView currentCart(UserContext user, String guestToken) {
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> view(scope, resolve(scope, user, guestToken)));
  }

  public CartView addItem(UserContext user, String guestToken, UUID offerId, int quantity) {
    if (offerId == null) throw DomainValidationException.of("offerId", "is required");
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var cart = resolve(scope, user, guestToken);
      var offer = listing.offer(scope, offerId).filter(OfferSnapshot::purchasable)
          .orElseThrow(() -> new DomainRuleException("OFFER_UNAVAILABLE", "This item is not available"));
      cart.add(Ids.newId(), offerId, quantity, offer.uniqueItem());
      carts.update(cart);
      return view(scope, cart);
    });
  }

  public CartView removeItem(UserContext user, String guestToken, UUID itemId) {
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var cart = resolve(scope, user, guestToken);
      cart.remove(itemId);
      carts.update(cart);
      return view(scope, cart);
    });
  }

  private Cart resolve(TenantScope scope, UserContext user, String guestToken) {
    if (user.authenticated()) return customerCart(scope, user.actorId(), guestToken);
    if (guestToken == null || guestToken.isBlank()) throw HiveException.notFound("Cart not found");
    return carts.findActiveByTokenHash(scope, hash(guestToken)).filter(c -> !c.expired(clock.instant()))
        .orElseThrow(() -> HiveException.notFound("Cart not found"));
  }

  /** The customer's active cart; a valid guest token presented by a signed-in customer is merged into it (ADR-0013). */
  private Cart customerCart(TenantScope scope, UUID customerId, String guestToken) {
    var cart = carts.findActiveByCustomer(scope, customerId).orElseGet(() -> {
      var created = Cart.forCustomer(Ids.newId(), scope, customerId);
      carts.insert(created);
      return created;
    });
    if (guestToken != null && !guestToken.isBlank()) {
      carts.findActiveByTokenHash(scope, hash(guestToken)).filter(g -> !g.expired(clock.instant())).ifPresent(guest -> {
        cart.absorb(guest, Ids::newId);
        carts.update(guest);
        carts.update(cart);
      });
    }
    return carts.findById(scope, cart.id()).orElseThrow();
  }

  private CartView view(TenantScope scope, Cart cart) {
    var lines = new ArrayList<CartLine>();
    var quoteLines = new ArrayList<PricingApi.Line>();
    boolean allPurchasable = true;
    for (var item : cart.items()) {
      var offer = listing.offer(scope, item.offerId()).orElse(null);
      if (offer == null) {
        allPurchasable = false;
        continue;
      }
      lines.add(new CartLine(item.id(), offer.offerId(), offer.productId(), offer.productSlug(), offer.productTitle(), offer.sku(), offer.variantAttributes(),
          offer.price().amount(), offer.price().currency().getCurrencyCode(), item.quantity(), offer.uniqueItem(), offer.purchasable()));
      allPurchasable &= offer.purchasable();
      quoteLines.add(new PricingApi.Line(item.id().toString(), offer.sellerId(), !offer.digital(), offer.price(), item.quantity()));
    }
    CartTotals totals = null;
    if (!quoteLines.isEmpty() && allPurchasable) {
      var q = pricing.quote(quoteLines);
      totals = new CartTotals(q.subtotal().amount(), q.tax().amount(), q.shipping().amount(), q.total().amount(), q.total().currency().getCurrencyCode());
    }
    return new CartView(cart.id(), cart.status().name(), lines, totals);
  }

  // ---------------------------------------------------------------- checkout saga

  public record CheckoutView(UUID checkoutId, String state, UUID orderId, String orderNumber, BigDecimal total, String currency, UUID paymentId,
      String redirectUrl, String failureCode) {
    static CheckoutView of(CheckoutAttempt a) {
      return new CheckoutView(a.id(), a.state().name(), a.orderId(), a.orderNumber(), a.total() == null ? null : a.total().amount(),
          a.total() == null ? null : a.total().currency().getCurrencyCode(), a.paymentId(), a.redirectUrl(), a.failureCode());
    }
  }

  private record Prepared(CheckoutAttempt attempt, List<OfferSnapshot> offers, List<Integer> quantities) {}

  public CheckoutView checkout(UserContext user, String idempotencyKey) {
    UUID customer = user.requireActor();
    if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) throw DomainValidationException.of("idempotencyKey", "8 to 100 characters [A-Za-z0-9._:-]");
    TenantScope scope = deployment.scope();

    // Step 0: start (or find) the attempt and snapshot the cart.
    Prepared prepared = transactions.inScope(scope, () -> {
      var existing = attempts.findByKey(scope, customer, idempotencyKey);
      if (existing.isPresent() && existing.get().settled()) return new Prepared(existing.get(), List.of(), List.of());
      var cart = carts.findActiveByCustomer(scope, customer).filter(c -> !c.items().isEmpty())
          .orElseThrow(() -> new DomainRuleException("CART_EMPTY", "The cart is empty"));
      var attempt = existing.orElseGet(() -> {
        var created = CheckoutAttempt.start(Ids.newId(), scope, customer, idempotencyKey, cart.id());
        attempts.insert(created);
        return created;
      });
      var offers = new ArrayList<OfferSnapshot>();
      var quantities = new ArrayList<Integer>();
      for (var item : cart.items()) {
        var offer = listing.offer(scope, item.offerId()).filter(OfferSnapshot::purchasable).orElse(null);
        if (offer == null) {
          if (attempt.state() == CheckoutAttempt.State.STARTED) {
            attempt.failed("OFFER_UNAVAILABLE");
            attempts.update(attempt);
          }
          return new Prepared(attempt, List.of(), List.of());
        }
        offers.add(offer);
        quantities.add(item.quantity());
      }
      return new Prepared(attempt, offers, quantities);
    });
    var attempt = prepared.attempt();
    if (attempt.settled() || attempt.state() == CheckoutAttempt.State.FAILED) return finish(attempt);

    var quoteLines = new ArrayList<PricingApi.Line>();
    for (int i = 0; i < prepared.offers().size(); i++) {
      var o = prepared.offers().get(i);
      quoteLines.add(new PricingApi.Line(o.offerId().toString(), o.sellerId(), !o.digital(), o.price(), prepared.quantities().get(i)));
    }
    var quote = pricing.quote(quoteLines);

    // Step 1: reserve stock (all lines or none; Inventory guarantees unique items are sold once).
    if (attempt.state() == CheckoutAttempt.State.STARTED) {
      try {
        inventory.reserve(scope, attempt.id(), prepared.offers().stream().map(o -> new InventoryApi.ReserveLine(o.offerId(),
            prepared.quantities().get(prepared.offers().indexOf(o)))).toList(), reservationTtl);
      } catch (DomainRuleException e) {
        record(scope, attempt.id(), a -> a.failed(e.code()));
        throw new HiveException(HttpStatus.CONFLICT, e.code(), e.getMessage());
      }
      attempt = record(scope, attempt.id(), CheckoutAttempt::reserved);
    }

    // Step 2: place the order with immutable snapshots, split per seller.
    if (attempt.state() == CheckoutAttempt.State.RESERVED) {
      try {
        var drafts = new ArrayList<OrderingApi.LineDraft>();
        for (int i = 0; i < prepared.offers().size(); i++) {
          var o = prepared.offers().get(i);
          var q = quote.lines().get(i);
          drafts.add(new OrderingApi.LineDraft(o.sellerId(), o.offerId(), o.productId(), o.variantId(), o.digital() ? "DIGITAL" : "PHYSICAL", o.sku(), o.productTitle(),
              o.variantAttributes(), o.price(), q.quantity(), q.lineTotal(), q.tax()));
        }
        var placed = ordering.placeOrder(scope, customer, attempt.id(), drafts, quote.shippingBySeller(), quote.total());
        attempt = record(scope, attempt.id(), a -> a.ordered(placed.orderId(), placed.number(), placed.total()));
      } catch (RuntimeException e) {
        inventory.release(scope, attempt.id());
        record(scope, attempt.id(), a -> a.compensated("ORDER_FAILED"));
        throw e;
      }
    }

    // Step 3: initiate payment with the provider.
    if (attempt.state() == CheckoutAttempt.State.ORDERED) {
      try {
        var start = payments.initiate(scope, attempt.orderId(), attempt.id(), customer, attempt.total());
        attempt = record(scope, attempt.id(), a -> a.paymentInitiated(start.paymentId(), start.redirectUrl()));
      } catch (RuntimeException e) {
        ordering.cancelUnpaid(scope, attempt.orderId(), "PAYMENT_INITIATION_FAILED");
        inventory.release(scope, attempt.id());
        record(scope, attempt.id(), a -> a.compensated("PAYMENT_INITIATION_FAILED"));
        throw e;
      }
      UUID cartId = attempt.cartId();
      transactions.inScope(scope, () -> {
        carts.findById(scope, cartId).filter(c -> c.status() == Cart.Status.ACTIVE).ifPresent(c -> {
          c.checkedOut();
          carts.update(c);
        });
        return null;
      });
    }
    return finish(attempt);
  }

  public CheckoutView attempt(UserContext user, UUID checkoutId) {
    UUID customer = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> attempts.findById(scope, checkoutId).filter(a -> a.customerId().equals(customer)).map(CheckoutView::of)
        .orElseThrow(() -> HiveException.notFound("Checkout not found")));
  }

  private CheckoutView finish(CheckoutAttempt attempt) {
    if (attempt.state() == CheckoutAttempt.State.FAILED) {
      throw new HiveException(HttpStatus.CONFLICT, attempt.failureCode() == null ? "CHECKOUT_FAILED" : attempt.failureCode(), "Checkout could not be completed");
    }
    return CheckoutView.of(attempt);
  }

  private CheckoutAttempt record(TenantScope scope, UUID attemptId, java.util.function.Consumer<CheckoutAttempt> change) {
    return transactions.inScope(scope, () -> {
      var attempt = attempts.findById(scope, attemptId).orElseThrow();
      change.accept(attempt);
      attempts.update(attempt);
      return attempts.findById(scope, attemptId).orElseThrow();
    });
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Ordering → Checkout: the saga ends when the order is paid or cancelled. */
  @Component
  static class OrderOutcomeHandler implements EventHandler {
    private final CheckoutRepositories.Attempts attempts;

    OrderOutcomeHandler(CheckoutRepositories.Attempts attempts) {
      this.attempts = attempts;
    }

    public String consumer() { return "checkout.order-outcome"; }
    public Set<String> eventTypes() { return Set.of("commerce.ordering.OrderPaid", "commerce.ordering.OrderCancelled"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      attempts.findById(scope, UUID.fromString((String) e.payload().get("checkoutAttemptId"))).ifPresent(a -> {
        if (e.type().endsWith("OrderPaid")) {
          if (a.state() == CheckoutAttempt.State.PAYMENT_INITIATED) a.completed();
        } else {
          a.compensated(String.valueOf(e.payload().get("reason")));
        }
        attempts.update(a);
      });
    }
  }
}
