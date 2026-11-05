package io.hivecommerce.digital.domain;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Port to private object storage (ADR-0008). Paid content never leaves storage except through short-lived signed URLs. */
public interface FileStorage {
  /** A browser form upload (POST policy) bound to one key, one content type and an exact size. */
  record UploadForm(String url, Map<String, String> fields, Instant expiresAt) {}

  record StoredObject(long size, String contentType) {}

  UploadForm presignUpload(String objectKey, String contentType, long size, Duration ttl);

  Optional<StoredObject> stat(String objectKey);

  InputStream open(String objectKey);

  void put(String objectKey, byte[] content, String contentType);

  /** A signed GET valid for {@code ttl}; {@code fileName} becomes the Content-Disposition attachment name. */
  String presignDownload(String objectKey, Duration ttl, String fileName);
}
