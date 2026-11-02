package io.hivecommerce.listing.domain;

import io.hivecommerce.listing.domain.event.ListingEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How a product is presented and published in a store channel (aggregate root). Invariant: only an approved listing is
 * published. Listings created by store operators are approved on creation; seller submissions wait for moderation.
 */
public final class Listing {
  public enum Status { PENDING_APPROVAL, APPROVED, PUBLISHED, UNPUBLISHED, REJECTED }

  public static final String DEFAULT_CHANNEL = "web";

  private final UUID id;
  private final TenantScope scope;
  private final UUID productId;
  private final String channel;
  private final UUID submittedBySeller;
  private Status status;
  private String rejectionReason;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Listing(UUID id, TenantScope scope, UUID productId, String channel, UUID submittedBySeller, Status status, String rejectionReason, long revision) {
    this.id = id;
    this.scope = scope;
    this.productId = productId;
    this.channel = channel;
    this.submittedBySeller = submittedBySeller;
    this.status = status;
    this.rejectionReason = rejectionReason;
    this.revision = revision;
  }

  public static Listing byOperator(UUID id, TenantScope scope, UUID productId, Instant now) {
    var listing = new Listing(id, scope, productId, DEFAULT_CHANNEL, null, Status.APPROVED, null, 0);
    listing.events.add(new ListingEvent(id, productId, "ListingApproved", now));
    return listing;
  }

  public static Listing bySeller(UUID id, TenantScope scope, UUID productId, UUID sellerId, Instant now) {
    var listing = new Listing(id, scope, productId, DEFAULT_CHANNEL, sellerId, Status.PENDING_APPROVAL, null, 0);
    listing.events.add(new ListingEvent(id, productId, "ApprovalRequested", now));
    return listing;
  }

  public static Listing restore(UUID id, TenantScope scope, UUID productId, String channel, UUID submittedBySeller, Status status, String rejectionReason,
      long revision) {
    return new Listing(id, scope, productId, channel, submittedBySeller, status, rejectionReason, revision);
  }

  public void approve(Instant now) {
    require(status == Status.PENDING_APPROVAL, "approve");
    status = Status.APPROVED;
    rejectionReason = null;
    events.add(new ListingEvent(id, productId, "ListingApproved", now));
  }

  public void reject(String reason, Instant now) {
    require(status == Status.PENDING_APPROVAL, "reject");
    if (reason == null || reason.isBlank() || reason.length() > 500) throw io.hivecommerce.shared.DomainValidationException.of("reason", "1 to 500 characters");
    status = Status.REJECTED;
    rejectionReason = reason.strip();
    events.add(new ListingEvent(id, productId, "ListingRejected", now));
  }

  public void resubmit(Instant now) {
    require(status == Status.REJECTED, "resubmit");
    status = Status.PENDING_APPROVAL;
    events.add(new ListingEvent(id, productId, "ApprovalRequested", now));
  }

  public void publish(Instant now) {
    if (status == Status.PUBLISHED) return;
    require(status == Status.APPROVED || status == Status.UNPUBLISHED, "publish");
    status = Status.PUBLISHED;
    events.add(new ListingEvent(id, productId, "ProductPublished", now));
  }

  public void unpublish(Instant now) {
    require(status == Status.PUBLISHED, "unpublish");
    status = Status.UNPUBLISHED;
    events.add(new ListingEvent(id, productId, "ProductUnpublished", now));
  }

  private void require(boolean allowed, String action) {
    if (!allowed) throw new DomainRuleException("LISTING_STATUS_INVALID", "Cannot " + action + " a listing in status " + status);
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID productId() { return productId; }
  public String channel() { return channel; }
  public UUID submittedBySeller() { return submittedBySeller; }
  public Status status() { return status; }
  public String rejectionReason() { return rejectionReason; }
  public long revision() { return revision; }
}
