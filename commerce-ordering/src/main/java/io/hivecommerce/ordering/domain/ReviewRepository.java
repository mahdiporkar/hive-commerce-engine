package io.hivecommerce.ordering.domain;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.UUID;

public interface ReviewRepository {
  void insert(ProductReview review);

  List<ProductReview> findVisibleByProduct(TenantScope scope, UUID productId, int limit);

  List<ProductReview> findRecent(TenantScope scope, int limit);

  boolean hide(TenantScope scope, UUID reviewId);
}
