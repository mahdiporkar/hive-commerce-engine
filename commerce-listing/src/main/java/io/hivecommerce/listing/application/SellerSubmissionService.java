package io.hivecommerce.listing.application;

import io.hivecommerce.catalog.application.published.CatalogCommandsApi;
import io.hivecommerce.listing.application.ListingAdminService.ListingView;
import io.hivecommerce.listing.domain.Listing;
import io.hivecommerce.listing.domain.ListingRepository;
import io.hivecommerce.listing.domain.OfferRepository;
import io.hivecommerce.listing.infrastructure.ListingEventsSink;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** Seller product submissions: the product is created and its listing waits for moderation (master prompt §14). */
@Service
public class SellerSubmissionService {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final SellerDirectory sellers;
  private final CatalogCommandsApi catalog;
  private final io.hivecommerce.catalog.application.published.CatalogQueries products;
  private final ListingRepository listings;
  private final Clock clock;

  public SellerSubmissionService(DeploymentScope deployment, TenantTransactions transactions, SellerDirectory sellers, CatalogCommandsApi catalog,
      io.hivecommerce.catalog.application.published.CatalogQueries products, ListingRepository listings, Clock clock) {
    this.products = products;
    this.deployment = deployment;
    this.transactions = transactions;
    this.sellers = sellers;
    this.catalog = catalog;
    this.listings = listings;
    this.clock = clock;
  }

  public ListingView submit(UserContext user, UUID sellerId, CatalogCommandsApi.ProductDraft draft) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "offers.manage")) throw HiveException.notFound("Seller not found");
      UUID productId = catalog.createProduct(scope, actor, draft);
      var listing = Listing.bySeller(Ids.newId(), scope, productId, sellerId, clock.instant());
      listings.insert(listing);
      listings.appendEvents(scope, listing.pullEvents());
      return ListingView.of(listing);
    });
  }

  /** A seller's listing with what the workspace needs to act on it: the product's address and purchasable variants. */
  public record SellerListingView(UUID listingId, UUID productId, String channel, String status, String rejectionReason, long revision, String slug, Map<String, String> title, List<UUID> variantIds) {}

  public List<SellerListingView> listings(UserContext user, UUID sellerId) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "offers.manage")) throw HiveException.notFound("Seller not found");
      return listings.findBySeller(scope, sellerId).stream().map(l -> {
        var view = ListingView.of(l);
        var product = products.product(scope, l.productId());
        return new SellerListingView(view.listingId(), view.productId(), view.channel(), view.status(), view.rejectionReason(), view.revision(),
            product.map(p -> p.slug()).orElse(null), product.map(p -> p.title()).orElse(Map.of()),
            product.map(p -> p.variants().stream().map(v -> v.variantId()).toList()).orElse(List.of()));
      }).toList();
    });
  }

  /** Catalog → Listing: a published listing shows the product as migrated, and its facets follow. */
  @Component
  static class ProductMigratedHandler implements EventHandler {
    private final ListingRepository listings;
    private final ListingProjection projection;
    private final io.hivecommerce.catalog.application.published.CatalogQueries products;

    ProductMigratedHandler(ListingRepository listings, ListingProjection projection, io.hivecommerce.catalog.application.published.CatalogQueries products) {
      this.listings = listings;
      this.projection = projection;
      this.products = products;
    }

    public String consumer() { return "listing.product-migrated"; }
    public Set<String> eventTypes() { return Set.of("commerce.catalog.ProductSchemaMigrated"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      UUID productId = UUID.fromString((String) e.payload().get("productId"));
      var listing = listings.findByProduct(scope, productId, io.hivecommerce.listing.domain.Listing.DEFAULT_CHANNEL).filter(l -> l.status() == io.hivecommerce.listing.domain.Listing.Status.PUBLISHED);
      if (listing.isEmpty()) return;
      products.product(scope, productId).ifPresent(p -> projection.upsert(scope, listing.get().id(), p, Facets.of(p)));
    }
  }

  /** Seller → Listing: a suspended seller's offers are withdrawn. */
  @Component
  static class SellerSuspendedHandler implements EventHandler {
    private final OfferRepository offers;
    private final ListingEventsSink events;
    private final Clock clock;

    SellerSuspendedHandler(OfferRepository offers, ListingEventsSink events, Clock clock) {
      this.offers = offers;
      this.events = events;
      this.clock = clock;
    }

    public String consumer() { return "listing.seller-suspended"; }
    public Set<String> eventTypes() { return Set.of("commerce.seller.SellerSuspended"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      for (var offer : offers.findBySeller(scope, UUID.fromString((String) e.payload().get("sellerId")))) {
        offer.withdraw(clock.instant());
        offers.update(offer);
        events.append(scope, offer.pullEvents());
      }
    }
  }
}
