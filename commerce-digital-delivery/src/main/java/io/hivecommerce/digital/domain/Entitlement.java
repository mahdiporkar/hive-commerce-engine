package io.hivecommerce.digital.domain;

import io.hivecommerce.digital.domain.event.DigitalEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A customer's right to a digital asset, from one paid order line (aggregate root). Invariants: granted only for a paid
 * line (created by the OrderPaid consumer, never by an API call); downloads never exceed the license limit; nothing is
 * issued after revocation. Buyers keep the version they bought, plus later versions when the license includes updates.
 */
public final class Entitlement {
  public enum Status { ACTIVE, REVOKED }

  private final UUID id;
  private final TenantScope scope;
  private final UUID customerId;
  private final UUID orderId;
  private final UUID orderLineId;
  private final UUID productId;
  private final String assetId;
  private final int grantedVersion;
  private final boolean updatesIncluded;
  private final Integer downloadLimit;
  private int downloadsUsed;
  private Status status;
  private String revokedReason;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Entitlement(UUID id, TenantScope scope, UUID customerId, UUID orderId, UUID orderLineId, UUID productId, String assetId, int grantedVersion,
      boolean updatesIncluded, Integer downloadLimit, int downloadsUsed, Status status, String revokedReason, long revision) {
    this.id = id;
    this.scope = scope;
    this.customerId = customerId;
    this.orderId = orderId;
    this.orderLineId = orderLineId;
    this.productId = productId;
    this.assetId = assetId;
    this.grantedVersion = grantedVersion;
    this.updatesIncluded = updatesIncluded;
    this.downloadLimit = downloadLimit;
    this.downloadsUsed = downloadsUsed;
    this.status = status;
    this.revokedReason = revokedReason;
    this.revision = revision;
  }

  public static Entitlement grant(UUID id, TenantScope scope, UUID customerId, UUID orderId, UUID orderLineId, UUID productId, DigitalAsset asset, Instant now) {
    int version = asset.latest().orElseThrow(() -> new DomainRuleException("ASSET_EMPTY", "The asset has no content yet")).number();
    var entitlement = new Entitlement(id, scope, customerId, orderId, orderLineId, productId, asset.id(), version, asset.license().updatesIncluded(),
        asset.license().downloadLimit(), 0, Status.ACTIVE, null, 0);
    entitlement.events.add(new DigitalEvent("EntitlementGranted", id.toString(), Map.of("entitlementId", id.toString(), "customerId", customerId.toString(),
        "productId", productId.toString(), "orderLineId", orderLineId.toString()), now));
    return entitlement;
  }

  public static Entitlement restore(UUID id, TenantScope scope, UUID customerId, UUID orderId, UUID orderLineId, UUID productId, String assetId, int grantedVersion,
      boolean updatesIncluded, Integer downloadLimit, int downloadsUsed, Status status, String revokedReason, long revision) {
    return new Entitlement(id, scope, customerId, orderId, orderLineId, productId, assetId, grantedVersion, updatesIncluded, downloadLimit, downloadsUsed,
        status, revokedReason, revision);
  }

  /** Consumes one download and returns the content version to serve. */
  public int issueDownload(int latestVersion, Instant now) {
    if (status == Status.REVOKED) throw new DomainRuleException("ENTITLEMENT_REVOKED", "Access to this content was revoked");
    if (downloadLimit != null && downloadsUsed >= downloadLimit) throw new DomainRuleException("DOWNLOAD_LIMIT_REACHED", "The download limit of this license is reached");
    downloadsUsed++;
    return updatesIncluded ? latestVersion : grantedVersion;
  }

  public boolean revoke(String reason, Instant now) {
    if (status == Status.REVOKED) return false;
    status = Status.REVOKED;
    revokedReason = reason == null || reason.isBlank() ? "REVOKED" : reason.strip();
    events.add(new DigitalEvent("EntitlementRevoked", id.toString(), Map.of("entitlementId", id.toString(), "customerId", customerId.toString(),
        "reason", revokedReason), now));
    return true;
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID customerId() { return customerId; }
  public UUID orderId() { return orderId; }
  public UUID orderLineId() { return orderLineId; }
  public UUID productId() { return productId; }
  public String assetId() { return assetId; }
  public int grantedVersion() { return grantedVersion; }
  public boolean updatesIncluded() { return updatesIncluded; }
  public Integer downloadLimit() { return downloadLimit; }
  public int downloadsUsed() { return downloadsUsed; }
  public Status status() { return status; }
  public String revokedReason() { return revokedReason; }
  public long revision() { return revision; }
}
