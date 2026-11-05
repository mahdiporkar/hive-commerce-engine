package io.hivecommerce.digital.domain;

import io.hivecommerce.digital.domain.event.DigitalEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Protected content sold with a digital product (aggregate root). Invariants: content versions are immutable and
 * numbered consecutively; a version exists only after a finalized, verified upload; the owner seller never changes;
 * the public preview is stored apart from paid content and never reveals it.
 */
public final class DigitalAsset {
  public enum Kind { FILE, TEXT }

  /** License terms granted with each purchase. A null download limit means unlimited downloads. */
  public record LicenseTerms(Integer downloadLimit, boolean updatesIncluded, String usage) {
    public LicenseTerms {
      if (downloadLimit != null && (downloadLimit < 1 || downloadLimit > 1000)) throw DomainValidationException.of("license.downloadLimit", "1 to 1000, or omitted");
      if (usage != null && usage.length() > 2000) throw DomainValidationException.of("license.usage", "at most 2000 characters");
    }
  }

  public record ContentVersion(int number, String objectKey, String sha256, long size, String mimeType, String scanStatus, Instant createdAt) {}

  private final String id;
  private final TenantScope scope;
  private final UUID ownerSellerId;
  private final UUID productId;
  private final Kind kind;
  private final String title;
  private final LicenseTerms license;
  private final List<ContentVersion> versions;
  private String previewText;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private DigitalAsset(String id, TenantScope scope, UUID ownerSellerId, UUID productId, Kind kind, String title, LicenseTerms license,
      List<ContentVersion> versions, String previewText, long revision) {
    this.id = id;
    this.scope = scope;
    this.ownerSellerId = ownerSellerId;
    this.productId = productId;
    this.kind = kind;
    this.title = title;
    this.license = license;
    this.versions = new ArrayList<>(versions);
    this.previewText = previewText;
    this.revision = revision;
  }

  public static DigitalAsset create(String id, TenantScope scope, UUID ownerSellerId, UUID productId, Kind kind, String title, LicenseTerms license, Instant now) {
    var v = new io.hivecommerce.shared.Violations();
    if (productId == null) v.add("productId", "is required");
    if (kind == null) v.add("kind", "FILE or TEXT");
    if (title == null || title.isBlank() || title.length() > 200) v.add("title", "1 to 200 characters");
    v.throwIfAny();
    var asset = new DigitalAsset(id, scope, ownerSellerId, productId, kind, title.strip(), license == null ? new LicenseTerms(null, true, null) : license,
        List.of(), null, 0);
    asset.events.add(new DigitalEvent("AssetCreated", id, Map.of("assetId", id, "productId", productId.toString(), "sellerId", ownerSellerId.toString()), now));
    return asset;
  }

  public static DigitalAsset restore(String id, TenantScope scope, UUID ownerSellerId, UUID productId, Kind kind, String title, LicenseTerms license,
      List<ContentVersion> versions, String previewText, long revision) {
    return new DigitalAsset(id, scope, ownerSellerId, productId, kind, title, license, versions, previewText, revision);
  }

  public ContentVersion addVersion(String objectKey, String sha256, long size, String mimeType, String scanStatus, Instant now) {
    if (kind == Kind.TEXT && !mimeType.startsWith("text/plain")) throw new DomainRuleException("ASSET_KIND_MISMATCH", "Text assets hold plain text only");
    var version = new ContentVersion(versions.size() + 1, objectKey, sha256, size, mimeType, scanStatus, now);
    versions.add(version);
    events.add(new DigitalEvent("ContentVersionAdded", id, Map.of("assetId", id, "version", version.number(), "productId", productId.toString()), now));
    return version;
  }

  public void setPreview(String text) {
    if (text != null && text.length() > 2000) throw DomainValidationException.of("preview", "at most 2000 characters");
    previewText = text == null || text.isBlank() ? null : text.strip();
  }

  public Optional<ContentVersion> latest() { return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast()); }

  public ContentVersion version(int number) {
    return versions.stream().filter(v -> v.number() == number).findFirst()
        .orElseThrow(() -> new DomainRuleException("ASSET_VERSION_UNKNOWN", "Unknown content version"));
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public String id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID ownerSellerId() { return ownerSellerId; }
  public UUID productId() { return productId; }
  public Kind kind() { return kind; }
  public String title() { return title; }
  public LicenseTerms license() { return license; }
  public List<ContentVersion> versions() { return List.copyOf(versions); }
  public String previewText() { return previewText; }
  public long revision() { return revision; }
}
