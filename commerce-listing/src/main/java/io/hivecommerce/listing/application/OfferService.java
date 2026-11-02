package io.hivecommerce.listing.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.inventory.application.published.InventoryApi;
import io.hivecommerce.listing.application.published.ListingQueries;
import io.hivecommerce.listing.domain.Listing;
import io.hivecommerce.listing.domain.ListingRepository;
import io.hivecommerce.listing.domain.OfferRepository;
import io.hivecommerce.listing.domain.SellerOffer;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.store.application.published.StoreDirectory;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Offer use cases. Operators make offers for the store's internal seller (Layer 1: commerce.api.catalog:update);
 * marketplace sellers make their own offers through the seller workspace (Layer 2: seller membership, ADR-0016).
 */
@Service
public class OfferService implements ListingQueries {
  public record OfferCommand(UUID variantId, BigDecimal amount, String currency, Integer quantity, String condition, boolean uniqueItem, String sellerSku) {}

  public record OfferView(UUID offerId, UUID sellerId, UUID productId, UUID variantId, BigDecimal price, String currency, String condition, boolean uniqueItem,
      boolean digital, String status, Integer available, long revision) {}

  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final OfferRepository offers;
  private final ListingRepository listings;
  private final CatalogQueries catalog;
  private final SellerDirectory sellers;
  private final StoreDirectory stores;
  private final InventoryApi inventory;
  private final io.hivecommerce.listing.infrastructure.ListingEventsSink events;
  private final Clock clock;

  public OfferService(DeploymentScope deployment, TenantTransactions transactions, OfferRepository offers, ListingRepository listings,
      CatalogQueries catalog, SellerDirectory sellers, StoreDirectory stores, InventoryApi inventory,
      io.hivecommerce.listing.infrastructure.ListingEventsSink events, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.offers = offers;
    this.listings = listings;
    this.catalog = catalog;
    this.sellers = sellers;
    this.stores = stores;
    this.inventory = inventory;
    this.events = events;
    this.clock = clock;
  }

  /** Operator: offer for the store's internal seller. */
  public OfferView createStoreOffer(UserContext user, OfferCommand command) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> create(scope, sellers.internalSellerId(scope), command));
  }

  /** Seller workspace: offer for a seller the caller may manage offers for (404 otherwise, no existence leak). */
  public OfferView createSellerOffer(UserContext user, UUID sellerId, OfferCommand command) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "offers.manage")) throw HiveException.notFound("Seller not found");
      return create(scope, sellerId, command);
    });
  }

  public List<OfferView> sellerOffers(UserContext user, UUID sellerId) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "offers.manage")) throw HiveException.notFound("Seller not found");
      return offers.findBySeller(scope, sellerId).stream().map(o -> view(o, null)).toList();
    });
  }

  private OfferView create(TenantScope scope, UUID sellerId, OfferCommand command) {
    if (command.variantId() == null) throw DomainValidationException.of("variantId", "is required");
    var variant = catalog.variant(scope, command.variantId()).orElseThrow(() -> DomainValidationException.of("variantId", "unknown variant"));
    var product = catalog.product(scope, variant.productId()).orElseThrow();
    var settings = stores.settings(scope);
    var seller = sellers.seller(scope, sellerId).orElseThrow(() -> HiveException.notFound("Seller not found"));
    Money price;
    try {
      price = command.amount() == null || command.currency() == null ? null : new Money(command.amount(), Currency.getInstance(command.currency()));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("price", e.getMessage());
    }
    SellerOffer.Condition condition;
    try {
      condition = command.condition() == null ? null : SellerOffer.Condition.valueOf(command.condition());
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("condition", "must be NEW, USED or REFURBISHED");
    }
    var offer = SellerOffer.create(Ids.newId(), scope, sellerId, "ACTIVE".equals(seller.status()), product.productId(), variant.variantId(), price,
        settings.currency(), condition, command.uniqueItem(), "DIGITAL".equals(product.kind()), command.quantity(), command.sellerSku(), clock.instant());
    offers.insert(offer);
    events.append(scope, offer.pullEvents());
    return view(offer, null);
  }

  /** Active offers of a product with current availability (for the public product page). */
  public List<OfferView> offersOfProduct(TenantScope scope, UUID productId) {
    var active = offers.findActiveByProduct(scope, productId);
    Map<UUID, Integer> available = inventory.availability(scope, active.stream().map(SellerOffer::id).toList());
    return active.stream().map(o -> view(o, o.digital() ? null : available.getOrDefault(o.id(), 0))).toList();
  }

  @Override
  public Optional<OfferSnapshot> offer(TenantScope scope, UUID offerId) {
    return offers.findById(scope, offerId).flatMap(o -> catalog.product(scope, o.productId()).map(p -> {
      boolean published = listings.findByProduct(scope, o.productId(), Listing.DEFAULT_CHANNEL).map(l -> l.status() == Listing.Status.PUBLISHED).orElse(false);
      var variant = p.variants().stream().filter(v -> v.variantId().equals(o.variantId())).findFirst().orElseThrow();
      return new OfferSnapshot(o.id(), o.sellerId(), o.productId(), o.variantId(), o.price(), o.uniqueItem(), o.digital(),
          published && o.status() == SellerOffer.Status.ACTIVE, variant.sku(), p.slug(), p.title(), variant.attributes());
    }));
  }

  private static OfferView view(SellerOffer o, Integer available) {
    return new OfferView(o.id(), o.sellerId(), o.productId(), o.variantId(), o.price().amount(), o.price().currency().getCurrencyCode(), o.condition().name(),
        o.uniqueItem(), o.digital(), o.status().name(), available, o.revision());
  }
}
