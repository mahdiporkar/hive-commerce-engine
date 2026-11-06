package io.hivecommerce.ordering.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import java.time.Instant;
import java.util.UUID;

/** A customer's review of a product they paid for. Invariants: rating 1 to 5, a body, one review per customer and product. */
public record ProductReview(UUID id, TenantScope scope, UUID productId, UUID customerId, int rating, String body, Status status, Instant createdAt) {
  public static final int MAX_BODY = 2000;

  public enum Status { VISIBLE, HIDDEN }

  public static ProductReview write(UUID id, TenantScope scope, UUID productId, UUID customerId, boolean purchased, int rating, String body, Instant now) {
    if (!purchased) throw new DomainRuleException("REVIEW_NEEDS_PURCHASE", "Only a customer who paid for this product can review it");
    var v = new Violations();
    if (rating < 1 || rating > 5) v.add("rating", "must be from 1 to 5");
    if (body == null || body.isBlank()) v.add("body", "is required");
    else if (body.length() > MAX_BODY) v.add("body", "at most " + MAX_BODY + " characters");
    v.throwIfAny();
    return new ProductReview(id, scope, productId, customerId, rating, body.strip(), Status.VISIBLE, now);
  }
}
