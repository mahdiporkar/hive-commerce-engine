package io.hivecommerce.listing.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.listing.application.ListingProjection.Detail;
import io.hivecommerce.listing.application.ListingProjection.Page;
import io.hivecommerce.listing.application.ListingProjection.SearchQuery;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.DomainValidationException;
import io.hiveplatform.spring.HiveException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Anonymous-safe storefront reads over the published-listing projection: search with category subtree, text and
 * attribute filters ({@code f.<key>=a,b}, {@code min.<key>}, {@code max.<key>}), sorting and paging, plus facet counts.
 */
@Service
public class ListingStorefrontService {
  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final int MAX_PAGE_SIZE = 100;
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final ListingProjection projection;
  private final CatalogQueries catalog;
  private final OfferService offerService;
  private final io.hivecommerce.platform.extensions.ExtensionRegistry extensions;

  public ListingStorefrontService(DeploymentScope deployment, TenantTransactions transactions, ListingProjection projection, CatalogQueries catalog,
      OfferService offerService, io.hivecommerce.platform.extensions.ExtensionRegistry extensions) {
    this.extensions = extensions;
    this.offerService = offerService;
    this.deployment = deployment;
    this.transactions = transactions;
    this.projection = projection;
    this.catalog = catalog;
  }

  public Page search(UserContext user, Map<String, String> params) {
    var equals = new LinkedHashMap<String, List<String>>();
    var min = new LinkedHashMap<String, BigDecimal>();
    var max = new LinkedHashMap<String, BigDecimal>();
    for (var entry : params.entrySet()) {
      String name = entry.getKey(), value = entry.getValue();
      if (name.startsWith("f.")) equals.put(key(name.substring(2)), List.of(value.split(",")).stream().map(String::strip).filter(s -> !s.isEmpty()).limit(20).toList());
      else if (name.startsWith("min.")) min.put(key(name.substring(4)), number(name, value));
      else if (name.startsWith("max.")) max.put(key(name.substring(4)), number(name, value));
    }
    if (equals.size() + min.size() + max.size() > 20) throw DomainValidationException.of("filters", "at most 20 filters");
    String sort = params.getOrDefault("sort", "newest");
    if (!List.of("newest", "title").contains(sort)) throw DomainValidationException.of("sort", "must be newest or title");
    int page = integer(params.getOrDefault("page", "0"), "page", 0, 10_000);
    int size = integer(params.getOrDefault("size", "20"), "size", 1, MAX_PAGE_SIZE);
    String text = params.get("q");
    if (text != null && text.length() > 100) throw DomainValidationException.of("q", "at most 100 characters");
    return transactions.inScope(deployment.scope(), () -> {
      List<UUID> categories = null;
      if (params.get("category") != null) {
        UUID categoryId = uuid(params.get("category"));
        categories = catalog.categorySubtree(deployment.scope(), categoryId);
        if (categories.isEmpty()) return new Page(List.of(), 0, page, size, List.of());
      }
      return projection.search(deployment.scope(), new SearchQuery(categories, text, equals, min, max, sort, page, size));
    });
  }

  /** Product detail with its active offers (price and current availability). */
  public record DetailWithOffers(Detail product, List<OfferService.OfferView> offers, Map<String, Map<String, Object>> extensions) {}

  public DetailWithOffers bySlug(UserContext user, String slug) {
    return transactions.inScope(deployment.scope(), () -> {
      var detail = projection.bySlug(deployment.scope(), slug).orElseThrow(() -> HiveException.notFound("Product not found"));
      var snapshot = catalog.product(deployment.scope(), detail.productId()).orElseThrow();
      return new DetailWithOffers(detail, offerService.offersOfProduct(deployment.scope(), detail.productId()),
          extensions.present(deployment.scope(), detail.productId(), new io.hivecommerce.spi.ProductFacts(snapshot.productTypeKey(), snapshot.kind(), snapshot.attributes(), snapshot.variants().stream().map(v -> v.attributes()).toList())));
    });
  }

  private static String key(String key) {
    if (!KEY.matcher(key).matches()) throw DomainValidationException.of("filters", "invalid attribute key " + key);
    return key;
  }

  private static BigDecimal number(String name, String value) {
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      throw DomainValidationException.of(name, "must be a number");
    }
  }

  private static int integer(String value, String name, int min, int max) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < min || parsed > max) throw DomainValidationException.of(name, "must be between " + min + " and " + max);
      return parsed;
    } catch (NumberFormatException e) {
      throw DomainValidationException.of(name, "must be a whole number");
    }
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("category", "must be a category id");
    }
  }
}
