package io.hivecommerce.ordering.application;

import io.hivecommerce.ordering.domain.OrderRepository;
import io.hivecommerce.ordering.domain.ProductReview;
import io.hivecommerce.ordering.domain.ReviewRepository;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Verified-purchase reviews, switched on per store (commerce.capabilities.reviews). Public views carry no customer identity. */
@Service
public class ReviewService {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final ReviewRepository reviews;
  private final OrderRepository orders;
  private final Clock clock;
  private final boolean enabled;

  public ReviewService(DeploymentScope deployment, TenantTransactions transactions, ReviewRepository reviews, OrderRepository orders, Clock clock,
      @Value("${commerce.capabilities.reviews:false}") boolean enabled) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.reviews = reviews;
    this.orders = orders;
    this.clock = clock;
    this.enabled = enabled;
  }

  public record PublicReview(int rating, String body, Instant createdAt) {}

  public record ProductReviews(boolean enabled, int count, BigDecimal averageRating, List<PublicReview> reviews) {}

  public record ReviewView(UUID reviewId, UUID productId, int rating, String body, String status, Instant createdAt) {
    static ReviewView of(ProductReview r) { return new ReviewView(r.id(), r.productId(), r.rating(), r.body(), r.status().name(), r.createdAt()); }
  }

  public ProductReviews forProduct(UUID productId) {
    if (!enabled) return new ProductReviews(false, 0, null, List.of());
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var visible = reviews.findVisibleByProduct(scope, productId, 100);
      BigDecimal average = visible.isEmpty() ? null : BigDecimal.valueOf(visible.stream().mapToInt(ProductReview::rating).average().orElse(0)).setScale(1, RoundingMode.HALF_UP);
      return new ProductReviews(true, visible.size(), average, visible.stream().map(r -> new PublicReview(r.rating(), r.body(), r.createdAt())).toList());
    });
  }

  public ReviewView write(UserContext user, UUID productId, int rating, String body) {
    UUID customer = user.requireActor();
    if (!enabled) throw new DomainRuleException("REVIEWS_DISABLED", "This store does not take reviews");
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var review = ProductReview.write(Ids.newId(), scope, productId, customer, orders.hasPaidFor(scope, customer, productId), rating, body, clock.instant());
      reviews.insert(review);
      return ReviewView.of(review);
    });
  }

  public List<ReviewView> recent(UserContext user) {
    TenantScope scope = operator(user);
    return transactions.inScope(scope, () -> reviews.findRecent(scope, 200).stream().map(ReviewView::of).toList());
  }

  public void hide(UserContext user, UUID reviewId) {
    TenantScope scope = operator(user);
    transactions.inScope(scope, () -> {
      if (!reviews.hide(scope, reviewId)) throw HiveException.notFound("Review not found");
      return null;
    });
  }

  private TenantScope operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    return deployment.scope();
  }
}
