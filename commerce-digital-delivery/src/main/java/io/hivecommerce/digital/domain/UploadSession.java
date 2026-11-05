package io.hivecommerce.digital.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A pre-signed, direct-to-storage upload awaiting verification (ADR-0008). Finalization accepts the object only when
 * size, SHA-256 and the content-sniffed MIME type match what was declared and the malware scan is clean.
 */
public final class UploadSession {
  public enum Status { PENDING, FINALIZED, REJECTED }

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private final String id;
  private final TenantScope scope;
  private final String assetId;
  private final String objectKey;
  private final long declaredSize;
  private final String declaredSha256;
  private final String declaredMime;
  private final Instant expiresAt;
  private Status status;
  private String rejection;

  private UploadSession(String id, TenantScope scope, String assetId, String objectKey, long declaredSize, String declaredSha256, String declaredMime,
      Instant expiresAt, Status status, String rejection) {
    this.id = id;
    this.scope = scope;
    this.assetId = assetId;
    this.objectKey = objectKey;
    this.declaredSize = declaredSize;
    this.declaredSha256 = declaredSha256;
    this.declaredMime = declaredMime;
    this.expiresAt = expiresAt;
    this.status = status;
    this.rejection = rejection;
  }

  public static UploadSession open(String id, TenantScope scope, String assetId, String objectKey, long size, String sha256, String mime, long maxSize,
      java.util.Set<String> allowedMimes, Instant expiresAt) {
    var v = new io.hivecommerce.shared.Violations();
    if (size < 1 || size > maxSize) v.add("size", "must be between 1 and " + maxSize + " bytes");
    if (sha256 == null || !SHA256.matcher(sha256).matches()) v.add("sha256", "lowercase hex SHA-256 of the file");
    if (mime == null || !allowedMimes.contains(mime)) v.add("contentType", "must be one of " + allowedMimes);
    v.throwIfAny();
    return new UploadSession(id, scope, assetId, objectKey, size, sha256, mime, expiresAt, Status.PENDING, null);
  }

  public static UploadSession restore(String id, TenantScope scope, String assetId, String objectKey, long size, String sha256, String mime, Instant expiresAt,
      Status status, String rejection) {
    return new UploadSession(id, scope, assetId, objectKey, size, sha256, mime, expiresAt, status, rejection);
  }

  /** Checks the stored object against the declaration; rejects the session (and throws) on any mismatch. */
  public void finalizeWith(long actualSize, String actualSha256, String sniffedMime, MalwareScanner.Result scan, Instant now) {
    if (status != Status.PENDING) throw new DomainRuleException("UPLOAD_CLOSED", "This upload was already " + status.name().toLowerCase());
    String problem = null;
    if (now.isAfter(expiresAt.plusSeconds(3600))) problem = "UPLOAD_EXPIRED";
    else if (actualSize != declaredSize) problem = "UPLOAD_SIZE_MISMATCH";
    else if (!declaredSha256.equals(actualSha256)) problem = "UPLOAD_CHECKSUM_MISMATCH";
    else if (!MimeSniffer.compatible(declaredMime, sniffedMime)) problem = "UPLOAD_TYPE_MISMATCH";
    else if (scan == MalwareScanner.Result.INFECTED) problem = "UPLOAD_MALWARE_DETECTED";
    if (problem != null) {
      status = Status.REJECTED;
      rejection = problem;
      return;
    }
    status = Status.FINALIZED;
  }

  public String id() { return id; }
  public TenantScope scope() { return scope; }
  public String assetId() { return assetId; }
  public String objectKey() { return objectKey; }
  public long declaredSize() { return declaredSize; }
  public String declaredSha256() { return declaredSha256; }
  public String declaredMime() { return declaredMime; }
  public Instant expiresAt() { return expiresAt; }
  public Status status() { return status; }
  public String rejection() { return rejection; }
}
