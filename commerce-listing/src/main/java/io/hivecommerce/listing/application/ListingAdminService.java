package io.hivecommerce.listing.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.listing.domain.Listing;
import io.hivecommerce.listing.domain.ListingRepository;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Operator listing use cases: publish a product into the store, unpublish, moderate seller submissions. */
@Service
public class ListingAdminService {
  public record ListingView(UUID listingId, UUID productId, String channel, String status, String rejectionReason, long revision) {
    static ListingView of(Listing l) { return new ListingView(l.id(), l.productId(), l.channel(), l.status().name(), l.rejectionReason(), l.revision()); }
  }

  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final ListingRepository listings;
  private final ListingProjection projection;
  private final CatalogQueries catalog;
  private final Clock clock;

  public ListingAdminService(DeploymentScope deployment, TenantTransactions transactions, ListingRepository listings, ListingProjection projection,
      CatalogQueries catalog, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.listings = listings;
    this.projection = projection;
    this.catalog = catalog;
    this.clock = clock;
  }

  /** Creates the operator's listing for the product if needed and publishes it with a fresh product snapshot. */
  public ListingView publishProduct(UserContext user, UUID productId) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var product = catalog.product(scope, productId).orElseThrow(() -> HiveException.notFound("Product not found"));
      var listing = listings.findByProduct(scope, productId, Listing.DEFAULT_CHANNEL).orElse(null);
      boolean created = listing == null;
      if (created) listing = Listing.byOperator(Ids.newId(), scope, productId, clock.instant());
      listing.publish(clock.instant());
      if (created) listings.insert(listing);
      else listings.update(listing);
      projection.upsert(scope, listing.id(), product, Facets.of(product));
      listings.appendEvents(scope, listing.pullEvents());
      return ListingView.of(listing);
    });
  }

  /** Moderator approval publishes the seller's listing with a fresh product snapshot. */
  public ListingView approve(UserContext user, UUID listingId) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var listing = listings.findById(scope, listingId).orElseThrow(() -> HiveException.notFound("Listing not found"));
      listing.approve(clock.instant());
      listing.publish(clock.instant());
      var product = catalog.product(scope, listing.productId()).orElseThrow();
      listings.update(listing);
      projection.upsert(scope, listing.id(), product, Facets.of(product));
      listings.appendEvents(scope, listing.pullEvents());
      return ListingView.of(listing);
    });
  }

  public java.util.List<ListingView> pending(UserContext user) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> listings.findByStatus(scope, Listing.Status.PENDING_APPROVAL).stream().map(ListingView::of).toList());
  }

  public ListingView reject(UserContext user, UUID listingId, String reason) {
    return change(user, listingId, listing -> listing.reject(reason, clock.instant()));
  }

  public ListingView unpublish(UserContext user, UUID listingId) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var listing = listings.findById(scope, listingId).orElseThrow(() -> HiveException.notFound("Listing not found"));
      listing.unpublish(clock.instant());
      listings.update(listing);
      projection.markUnpublished(scope, listingId);
      listings.appendEvents(scope, listing.pullEvents());
      return ListingView.of(listing);
    });
  }

  private ListingView change(UserContext user, UUID listingId, java.util.function.Consumer<Listing> action) {
    TenantScope scope = operatorScope(user);
    return transactions.inScope(scope, () -> {
      var listing = listings.findById(scope, listingId).orElseThrow(() -> HiveException.notFound("Listing not found"));
      action.accept(listing);
      listings.update(listing);
      listings.appendEvents(scope, listing.pullEvents());
      return ListingView.of(listing);
    });
  }

  private TenantScope operatorScope(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    return deployment.scope();
  }
}
