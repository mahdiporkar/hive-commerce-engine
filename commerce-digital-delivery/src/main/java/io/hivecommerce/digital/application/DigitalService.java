package io.hivecommerce.digital.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.digital.domain.DigitalAsset;
import io.hivecommerce.digital.domain.DigitalRepositories;
import io.hivecommerce.digital.domain.Entitlement;
import io.hivecommerce.digital.domain.FileStorage;
import io.hivecommerce.digital.domain.MalwareScanner;
import io.hivecommerce.digital.domain.MimeSniffer;
import io.hivecommerce.digital.domain.RandomIds;
import io.hivecommerce.digital.domain.UploadSession;
import io.hivecommerce.digital.infrastructure.DigitalOutbox;
import io.hivecommerce.platform.events.EventEnvelope;
import io.hivecommerce.platform.events.EventHandler;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.TenantScope;
import io.hiveplatform.spring.HiveException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Digital delivery use cases (master prompt §13, ADR-0008). Asset management is for the owning seller (operators act for
 * the internal seller); entitlements come only from paid orders; downloads are short-lived signed URLs (or inline text
 * for small prompts) issued after ownership, revocation and limit checks, and logged. Content can be copied once
 * delivered; no DRM is claimed.
 */
@Service
public class DigitalService {
  private static final Duration MAX_DOWNLOAD_TTL = Duration.ofSeconds(300);
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final DigitalRepositories.Assets assets;
  private final DigitalRepositories.Uploads uploads;
  private final DigitalRepositories.Entitlements entitlements;
  private final ObjectProvider<FileStorage> storage;
  private final MalwareScanner scanner;
  private final CatalogQueries catalog;
  private final SellerDirectory sellers;
  private final DigitalOutbox outbox;
  private final Clock clock;
  private final Duration downloadTtl;
  private final Duration uploadTtl;
  private final long maxUploadBytes;
  private final int inlineTextMaxBytes;
  private final boolean requireScan;
  private final Set<String> allowedTypes;

  public DigitalService(DeploymentScope deployment, TenantTransactions transactions, DigitalRepositories.Assets assets, DigitalRepositories.Uploads uploads,
      DigitalRepositories.Entitlements entitlements, ObjectProvider<FileStorage> storage, MalwareScanner scanner, CatalogQueries catalog,
      SellerDirectory sellers, DigitalOutbox outbox, Clock clock, @Value("${commerce.digital.download-ttl:PT5M}") Duration downloadTtl,
      @Value("${commerce.digital.upload-ttl:PT15M}") Duration uploadTtl, @Value("${commerce.digital.max-upload-bytes:2147483648}") long maxUploadBytes,
      @Value("${commerce.digital.inline-text-max-bytes:65536}") int inlineTextMaxBytes, @Value("${commerce.digital.require-scan:false}") boolean requireScan,
      @Value("${commerce.digital.allowed-types:application/pdf,application/epub+zip,application/zip,image/png,image/jpeg,audio/mpeg,video/mp4,text/plain}")
      List<String> allowedTypes) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.assets = assets;
    this.uploads = uploads;
    this.entitlements = entitlements;
    this.storage = storage;
    this.scanner = scanner;
    this.catalog = catalog;
    this.sellers = sellers;
    this.outbox = outbox;
    this.clock = clock;
    this.downloadTtl = downloadTtl.compareTo(MAX_DOWNLOAD_TTL) > 0 ? MAX_DOWNLOAD_TTL : downloadTtl;
    this.uploadTtl = uploadTtl;
    this.maxUploadBytes = maxUploadBytes;
    this.inlineTextMaxBytes = inlineTextMaxBytes;
    this.requireScan = requireScan;
    this.allowedTypes = new LinkedHashSet<>(allowedTypes);
  }

  // ---------------------------------------------------------------- views

  public record AssetVersionView(int version, long size, String mimeType, String sha256, String scanStatus, Instant createdAt) {}

  public record AssetView(String assetId, UUID productId, UUID sellerId, String kind, String title, Integer downloadLimit, boolean updatesIncluded,
      String preview, List<AssetVersionView> versions) {
    static AssetView of(DigitalAsset a) {
      return new AssetView(a.id(), a.productId(), a.ownerSellerId(), a.kind().name(), a.title(), a.license().downloadLimit(), a.license().updatesIncluded(),
          a.previewText(), a.versions().stream().map(v -> new AssetVersionView(v.number(), v.size(), v.mimeType(), v.sha256(), v.scanStatus(), v.createdAt())).toList());
    }
  }

  public record AssetCommand(UUID productId, String kind, String title, Integer downloadLimit, Boolean updatesIncluded, String licenseUsage) {}

  public record UploadView(String uploadId, String url, Map<String, String> fields, Instant expiresAt) {}

  public record LibraryItem(UUID entitlementId, UUID productId, UUID orderId, String title, String kind, String status, Integer downloadLimit, int downloadsUsed,
      int version) {}

  public record Download(String url, Instant expiresAt, String content, String mimeType, int version) {}

  public record PublicPreview(String title, String kind, String preview) {}

  // ---------------------------------------------------------------- asset management

  /** Operator: assets owned by the store's internal seller. */
  public AssetView createStoreAsset(UserContext user, AssetCommand command) {
    operator(user);
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> create(scope, sellers.internalSellerId(scope), command));
  }

  /** Seller workspace: assets of a seller the caller may manage (404 otherwise). */
  public AssetView createSellerAsset(UserContext user, UUID sellerId, AssetCommand command) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!sellers.allows(scope, actor, sellerId, "assets.manage")) throw HiveException.notFound("Seller not found");
      return create(scope, sellerId, command);
    });
  }

  private AssetView create(TenantScope scope, UUID sellerId, AssetCommand c) {
    var product = catalog.product(scope, c.productId()).orElseThrow(() -> DomainValidationException.of("productId", "unknown product"));
    if (!"DIGITAL".equals(product.kind())) throw DomainValidationException.of("productId", "the product type is not DIGITAL");
    DigitalAsset.Kind kind;
    try {
      kind = DigitalAsset.Kind.valueOf(String.valueOf(c.kind()));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("kind", "FILE or TEXT");
    }
    var asset = DigitalAsset.create(RandomIds.next("da_"), scope, sellerId, c.productId(), kind, c.title(),
        new DigitalAsset.LicenseTerms(c.downloadLimit(), c.updatesIncluded() == null || c.updatesIncluded(), c.licenseUsage()), clock.instant());
    assets.insert(asset);
    outbox.append(scope, asset.pullEvents());
    return AssetView.of(asset);
  }

  public UploadView requestUpload(UserContext user, String assetId, BiPredicate<UUID, String> mayManage, String contentType, long size, String sha256) {
    TenantScope scope = deployment.scope();
    var files = files();
    var session = transactions.inScope(scope, () -> {
      var asset = owned(scope, assetId, mayManage);
      if (asset.kind() != DigitalAsset.Kind.FILE) throw new DomainRuleException("ASSET_KIND_MISMATCH", "Text assets take their content through the text endpoint");
      String uploadId = RandomIds.next("up_");
      String key = objectKey(scope, asset.id(), uploadId);
      var upload = UploadSession.open(uploadId, scope, asset.id(), key, size, sha256 == null ? null : sha256.toLowerCase(), contentType, maxUploadBytes,
          allowedTypes, clock.instant().plus(uploadTtl));
      uploads.insert(upload);
      return upload;
    });
    var form = files.presignUpload(session.objectKey(), session.declaredMime(), session.declaredSize(), uploadTtl);
    return new UploadView(session.id(), form.url(), form.fields(), form.expiresAt());
  }

  public AssetView finalizeUpload(UserContext user, String assetId, String uploadId, BiPredicate<UUID, String> mayManage) {
    TenantScope scope = deployment.scope();
    var files = files();
    var session = transactions.inScope(scope, () -> {
      owned(scope, assetId, mayManage);
      return uploads.findById(scope, uploadId).filter(u -> u.assetId().equals(assetId)).orElseThrow(() -> HiveException.notFound("Upload not found"));
    });
    var stored = files.stat(session.objectKey()).orElseThrow(() -> new DomainRuleException("UPLOAD_MISSING", "Nothing was uploaded yet"));
    // Verification reads the stored object outside the database transaction: checksum, content type, malware scan.
    String sha;
    byte[] head;
    try (InputStream in = files.open(session.objectKey())) {
      var digest = MessageDigest.getInstance("SHA-256");
      head = in.readNBytes(512);
      digest.update(head);
      byte[] buffer = new byte[64 * 1024];
      for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
      sha = HexFormat.of().formatHex(digest.digest());
    } catch (IOException | java.security.NoSuchAlgorithmException e) {
      throw new HiveException(HttpStatus.BAD_GATEWAY, "STORAGE_UNAVAILABLE", "Stored file could not be read");
    }
    MalwareScanner.Result scan;
    try (InputStream in = files.open(session.objectKey())) {
      scan = scanner.scan(in);
    } catch (IOException e) {
      throw new HiveException(HttpStatus.BAD_GATEWAY, "STORAGE_UNAVAILABLE", "Stored file could not be read");
    }
    if (requireScan && scan == MalwareScanner.Result.NOT_SCANNED) throw new HiveException(HttpStatus.SERVICE_UNAVAILABLE, "SCANNER_UNAVAILABLE", "A malware scan is required");
    String sniffed = MimeSniffer.detect(head);
    var result = transactions.inScope(scope, () -> {
      var upload = uploads.findById(scope, uploadId).orElseThrow();
      upload.finalizeWith(stored.size(), sha, sniffed, scan, clock.instant());
      uploads.update(upload);
      if (upload.status() == UploadSession.Status.REJECTED) return upload.rejection();
      var asset = assets.findById(scope, assetId).orElseThrow();
      asset.addVersion(upload.objectKey(), sha, stored.size(), upload.declaredMime(), scan.name(), clock.instant());
      assets.update(asset);
      outbox.append(scope, asset.pullEvents());
      return null;
    });
    if (result != null) throw new DomainRuleException(result, "The uploaded file was rejected (" + result + ")");
    return transactions.inScope(scope, () -> AssetView.of(assets.findById(scope, assetId).orElseThrow()));
  }

  /** Text content (prompts): small enough to go through the BFF; stored privately like any file. */
  public AssetView putText(UserContext user, String assetId, BiPredicate<UUID, String> mayManage, String content) {
    if (content == null || content.isBlank()) throw DomainValidationException.of("content", "must not be blank");
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > inlineTextMaxBytes) throw DomainValidationException.of("content", "at most " + inlineTextMaxBytes + " bytes");
    TenantScope scope = deployment.scope();
    var files = files();
    var asset = transactions.inScope(scope, () -> owned(scope, assetId, mayManage));
    if (asset.kind() != DigitalAsset.Kind.TEXT) throw new DomainRuleException("ASSET_KIND_MISMATCH", "File assets are uploaded through pre-signed uploads");
    String key = objectKey(scope, assetId, RandomIds.next("tx_"));
    files.put(key, bytes, "text/plain; charset=utf-8");
    String sha;
    try {
      sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    var scan = scanner.scan(new java.io.ByteArrayInputStream(bytes));
    if (scan == MalwareScanner.Result.INFECTED) throw new DomainRuleException("UPLOAD_MALWARE_DETECTED", "The content was rejected");
    return transactions.inScope(scope, () -> {
      var current = assets.findById(scope, assetId).orElseThrow();
      current.addVersion(key, sha, bytes.length, "text/plain; charset=utf-8", scan.name(), clock.instant());
      assets.update(current);
      outbox.append(scope, current.pullEvents());
      return AssetView.of(current);
    });
  }

  public AssetView setPreview(UserContext user, String assetId, BiPredicate<UUID, String> mayManage, String preview) {
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var asset = owned(scope, assetId, mayManage);
      asset.setPreview(preview);
      assets.update(asset);
      return AssetView.of(asset);
    });
  }

  /** Public previews of a product's assets: titles and preview text only — never object keys or paid content. */
  public List<PublicPreview> previews(UUID productId) {
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> assets.findByProduct(scope, productId).stream()
        .map(a -> new PublicPreview(a.title(), a.kind().name(), a.previewText())).toList());
  }

  /** Predicate for operators: they manage the internal seller's assets. */
  public BiPredicate<UUID, String> operatorAccess(UserContext user) {
    operator(user);
    UUID internal = transactions.inScope(deployment.scope(), () -> sellers.internalSellerId(deployment.scope()));
    return (sellerId, capability) -> internal.equals(sellerId);
  }

  /** Predicate for seller members holding {@code assets.manage}. */
  public BiPredicate<UUID, String> sellerAccess(UserContext user) {
    UUID actor = user.requireActor();
    return (sellerId, capability) -> sellers.allows(deployment.scope(), actor, sellerId, "assets.manage");
  }

  private DigitalAsset owned(TenantScope scope, String assetId, BiPredicate<UUID, String> mayManage) {
    return assets.findById(scope, assetId).filter(a -> mayManage.test(a.ownerSellerId(), "assets.manage"))
        .orElseThrow(() -> HiveException.notFound("Asset not found"));
  }

  // ---------------------------------------------------------------- customer library and downloads

  public List<LibraryItem> library(UserContext user) {
    UUID customer = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> entitlements.findByCustomer(scope, customer).stream().map(e -> {
      var asset = assets.findById(scope, e.assetId()).orElseThrow();
      int version = e.updatesIncluded() ? asset.latest().orElseThrow().number() : e.grantedVersion();
      return new LibraryItem(e.id(), e.productId(), e.orderId(), asset.title(), asset.kind().name(), e.status().name(), e.downloadLimit(), e.downloadsUsed(), version);
    }).toList());
  }

  public Download download(UserContext user, UUID entitlementId) {
    UUID customer = user.requireActor();
    TenantScope scope = deployment.scope();
    var files = files();
    record Issued(Entitlement entitlement, DigitalAsset.ContentVersion version, DigitalAsset asset) {}
    var issued = transactions.inScope(scope, () -> {
      var entitlement = entitlements.findById(scope, entitlementId).filter(e -> e.customerId().equals(customer))
          .orElseThrow(() -> HiveException.notFound("Not in your library"));
      var asset = assets.findById(scope, entitlement.assetId()).orElseThrow();
      int versionNumber = entitlement.issueDownload(asset.latest().orElseThrow().number(), clock.instant());
      entitlements.update(entitlement);
      return new Issued(entitlement, asset.version(versionNumber), asset);
    });
    var v = issued.version();
    if (v.mimeType().startsWith("text/plain") && v.size() <= inlineTextMaxBytes) {
      try (InputStream in = files.open(v.objectKey())) {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        transactions.inScope(scope, () -> { entitlements.logAccess(issued.entitlement(), v.number(), null, "INLINE"); return null; });
        return new Download(null, null, content, "text/plain", v.number());
      } catch (IOException e) {
        throw new HiveException(HttpStatus.BAD_GATEWAY, "STORAGE_UNAVAILABLE", "Content could not be read");
      }
    }
    Instant expiresAt = clock.instant().plus(downloadTtl);
    String url = files.presignDownload(v.objectKey(), downloadTtl, fileName(issued.asset(), v));
    transactions.inScope(scope, () -> { entitlements.logAccess(issued.entitlement(), v.number(), expiresAt, "SIGNED_URL"); return null; });
    return new Download(url, expiresAt, null, null, v.number());
  }

  public LibraryItem revoke(UserContext user, UUID entitlementId, String reason) {
    operator(user);
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var e = entitlements.findById(scope, entitlementId).orElseThrow(() -> HiveException.notFound("Entitlement not found"));
      if (e.revoke(reason, clock.instant())) {
        entitlements.update(e);
        outbox.append(scope, e.pullEvents());
      }
      var asset = assets.findById(scope, e.assetId()).orElseThrow();
      return new LibraryItem(e.id(), e.productId(), e.orderId(), asset.title(), asset.kind().name(), e.status().name(), e.downloadLimit(), e.downloadsUsed(),
          e.grantedVersion());
    });
  }

  private static String fileName(DigitalAsset asset, DigitalAsset.ContentVersion v) {
    String base = asset.title().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-");
    String extension = switch (v.mimeType().split(";")[0]) {
      case "application/pdf" -> ".pdf"; case "application/epub+zip" -> ".epub"; case "application/zip" -> ".zip"; case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg"; case "audio/mpeg" -> ".mp3"; case "video/mp4" -> ".mp4"; default -> ".txt";
    };
    return (base.isBlank() ? "download" : base) + "-v" + v.number() + extension;
  }

  private static String objectKey(TenantScope scope, String assetId, String unique) {
    return "t/" + scope.tenantId() + "/s/" + scope.storeId() + "/assets/" + assetId + "/" + unique;
  }

  private FileStorage files() {
    var files = storage.getIfAvailable();
    if (files == null) throw new HiveException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_CONFIGURED", "Digital delivery is not configured");
    return files;
  }

  private void operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
  }

  // ---------------------------------------------------------------- event consumers

  /** Ordering → Digital Delivery: paid digital lines grant entitlements (the only way an entitlement is created). */
  @Component
  static class OrderPaidHandler implements EventHandler {
    private final DigitalRepositories.Assets assets;
    private final DigitalRepositories.Entitlements entitlements;
    private final DigitalOutbox outbox;
    private final Clock clock;

    OrderPaidHandler(DigitalRepositories.Assets assets, DigitalRepositories.Entitlements entitlements, DigitalOutbox outbox, Clock clock) {
      this.assets = assets;
      this.entitlements = entitlements;
      this.outbox = outbox;
      this.clock = clock;
    }

    public String consumer() { return "digital.order-paid"; }
    public Set<String> eventTypes() { return Set.of("commerce.ordering.OrderPaid"); }

    @SuppressWarnings("unchecked")
    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      var p = e.payload();
      UUID customer = UUID.fromString((String) p.get("customerId"));
      UUID orderId = UUID.fromString((String) p.get("orderId"));
      var deliveredSellerOrders = new LinkedHashSet<String>();
      var incomplete = new LinkedHashSet<String>();
      for (var line : (List<Map<String, Object>>) p.get("lines")) {
        if (!"DIGITAL".equals(line.get("kind"))) continue;
        String sellerOrderId = (String) line.get("sellerOrderId");
        var productAssets = assets.findByProduct(scope, UUID.fromString((String) line.get("productId"))).stream().filter(a -> a.latest().isPresent()).toList();
        if (productAssets.isEmpty()) incomplete.add(sellerOrderId);
        for (var asset : productAssets) {
          var entitlement = Entitlement.grant(Ids.newId(), scope, customer, orderId, UUID.fromString((String) line.get("lineId")),
              UUID.fromString((String) line.get("productId")), asset, clock.instant());
          if (entitlements.insertIfAbsent(entitlement)) outbox.append(scope, entitlement.pullEvents());
        }
        deliveredSellerOrders.add(sellerOrderId);
      }
      for (var so : (List<Map<String, Object>>) p.get("sellerOrders")) {
        String id = (String) so.get("sellerOrderId");
        if (Boolean.TRUE.equals(so.get("hasPhysical")) || !deliveredSellerOrders.contains(id) || incomplete.contains(id)) continue;
        outbox.append(scope, List.of(new io.hivecommerce.digital.domain.event.DigitalEvent("DigitalDelivered", id,
            Map.of("orderId", orderId.toString(), "sellerOrderId", id), clock.instant())));
      }
    }
  }

  /** Ordering → Digital Delivery: a refunded line loses its entitlements. */
  @Component
  static class LineRefundedHandler implements EventHandler {
    private final DigitalRepositories.Entitlements entitlements;
    private final DigitalOutbox outbox;
    private final Clock clock;

    LineRefundedHandler(DigitalRepositories.Entitlements entitlements, DigitalOutbox outbox, Clock clock) {
      this.entitlements = entitlements;
      this.outbox = outbox;
      this.clock = clock;
    }

    public String consumer() { return "digital.line-refunded"; }
    public Set<String> eventTypes() { return Set.of("commerce.ordering.OrderLineRefunded"); }

    public void handle(EventEnvelope e) {
      var scope = new TenantScope(e.tenantId(), e.storeId());
      for (var entitlement : entitlements.findByOrderLine(scope, UUID.fromString((String) e.payload().get("lineId")))) {
        if (entitlement.revoke("REFUNDED", clock.instant())) {
          entitlements.update(entitlement);
          outbox.append(scope, entitlement.pullEvents());
        }
      }
    }
  }
}
