package io.hivecommerce.platform.extensions;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import io.hivecommerce.spi.CommerceExtension;
import io.hivecommerce.spi.ExtensionData;
import io.hivecommerce.spi.ExtensionDataHandler;
import io.hivecommerce.spi.Issue;
import io.hivecommerce.spi.ListingApprovalExtension;
import io.hivecommerce.spi.ProductFacts;
import io.hivecommerce.spi.ProductPresentationExtension;
import io.hivecommerce.spi.ProductValidationExtension;
import io.hivecommerce.spi.SellerVerificationExtension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Finds extensions with {@link ServiceLoader} and calls them fail-closed: reported issues reject the command, and an
 * exception rejects it with {@code EXTENSION_REJECTED}. An engine without extensions behaves normally. A presentation
 * failure only omits that extension's block, because it cannot weaken anything.
 */
@Component
public class ExtensionRegistry {
  private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);
  private final List<CommerceExtension> extensions;
  private final ExtensionDocuments documents;

  @Autowired
  public ExtensionRegistry(ExtensionDocuments documents) {
    this(documents, load());
  }

  ExtensionRegistry(ExtensionDocuments documents, List<CommerceExtension> extensions) {
    this.documents = documents;
    this.extensions = List.copyOf(extensions);
    var keys = new HashSet<String>();
    for (var extension : extensions) {
      if (extension.spiMajor() != CommerceExtension.SPI_MAJOR) {
        throw new IllegalStateException("Extension " + extension.key() + " needs SPI major " + extension.spiMajor() + "; this engine provides " + CommerceExtension.SPI_MAJOR);
      }
      if (!keys.add(extension.key())) throw new IllegalStateException("Duplicate extension key " + extension.key());
      log.info("Loaded commerce extension {}", extension.key());
    }
  }

  private static List<CommerceExtension> load() {
    var loaded = new ArrayList<CommerceExtension>();
    ServiceLoader.load(CommerceExtension.class, Thread.currentThread().getContextClassLoader()).forEach(loaded::add);
    return loaded;
  }

  public void validateProduct(ProductFacts product) {
    collect(ProductValidationExtension.class, e -> e.validate(product));
  }

  public void reviewForPublication(TenantScope scope, UUID productId, ProductFacts product) {
    collect(ListingApprovalExtension.class, e -> e.reviewForPublication(productId, product, data(scope, e)));
  }

  public void reviewSeller(TenantScope scope, UUID sellerId) {
    collect(SellerVerificationExtension.class, e -> e.reviewForVerification(sellerId, data(scope, e)));
  }

  /** Public data per extension key. */
  public Map<String, Map<String, Object>> present(TenantScope scope, UUID productId, ProductFacts product) {
    var result = new LinkedHashMap<String, Map<String, Object>>();
    for (var e : extensions) {
      if (!(e instanceof ProductPresentationExtension presentation)) continue;
      try {
        var block = presentation.present(productId, product, data(scope, e));
        if (block != null && !block.isEmpty()) result.put(e.key(), block);
      } catch (RuntimeException failure) {
        log.warn("Presentation extension {} failed", e.key(), failure);
      }
    }
    return result;
  }

  public Optional<ExtensionDataHandler> dataHandler(String key) {
    return extensions.stream().filter(e -> e.key().equals(key) && e instanceof ExtensionDataHandler).map(ExtensionDataHandler.class::cast).findFirst();
  }

  private ExtensionData data(TenantScope scope, CommerceExtension extension) {
    return (subjectType, subjectId) -> documents.find(scope, extension.key(), subjectType, subjectId);
  }

  private <T extends CommerceExtension> void collect(Class<T> type, java.util.function.Function<T, List<Issue>> call) {
    var violations = new Violations();
    for (var e : extensions) {
      if (!type.isInstance(e)) continue;
      List<Issue> issues = guarded(e, () -> call.apply(type.cast(e)));
      if (issues != null) issues.forEach(i -> violations.add(i.field(), i.message()));
    }
    violations.throwIfAny();
  }

  private static <R> R guarded(CommerceExtension e, Supplier<R> call) {
    try {
      return call.get();
    } catch (DomainValidationException | DomainRuleException known) {
      throw known;
    } catch (RuntimeException failure) {
      log.error("Extension {} failed; rejecting the command", e.key(), failure);
      throw new DomainRuleException("EXTENSION_REJECTED", "The command was rejected by extension " + e.key());
    }
  }

  public List<String> keys() { return extensions.stream().map(CommerceExtension::key).toList(); }

  /** Requires a loaded handler for writes. */
  public void requireKnown(String key) {
    if (dataHandler(key).isEmpty()) throw new DomainRuleException("EXTENSION_UNKNOWN", "No extension accepts data under this key");
  }

}
