package io.hivecommerce.digital.infrastructure;

import io.hivecommerce.digital.domain.FileStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * S3-compatible storage through the MinIO client (works with MinIO, AWS S3 and other S3 APIs). Server-side calls use the
 * internal endpoint; signed URLs are generated against the public endpoint browsers can reach. The bucket stays private.
 */
@Component
@ConditionalOnProperty(name = "commerce.storage.endpoint")
class MinioFileStorage implements FileStorage {
  private final MinioClient internal;
  private final MinioClient signer;
  private final String bucket;
  private final String uploadUrl;

  MinioFileStorage(@Value("${commerce.storage.endpoint}") String endpoint, @Value("${commerce.storage.public-endpoint:${commerce.storage.endpoint}}") String publicEndpoint,
      @Value("${commerce.storage.access-key}") String accessKey, @Value("${commerce.storage.secret-key}") String secretKey,
      @Value("${commerce.storage.bucket}") String bucket, @Value("${commerce.storage.region:us-east-1}") String region,
      @Value("${commerce.storage.create-bucket:false}") boolean createBucket) throws Exception {
    this.internal = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).region(region).build();
    this.signer = MinioClient.builder().endpoint(publicEndpoint).credentials(accessKey, secretKey).region(region).build();
    this.bucket = bucket;
    this.uploadUrl = publicEndpoint.replaceAll("/+$", "") + "/" + bucket;
    if (createBucket && !internal.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
      internal.makeBucket(MakeBucketArgs.builder().bucket(bucket).region(region).build());
    }
  }

  @Override
  public UploadForm presignUpload(String objectKey, String contentType, long size, Duration ttl) {
    try {
      var expiry = ZonedDateTime.now().plus(ttl);
      var policy = new PostPolicy(bucket, expiry);
      policy.addEqualsCondition("key", objectKey);
      policy.addEqualsCondition("Content-Type", contentType);
      policy.addContentLengthRangeCondition(size, size);
      Map<String, String> fields = new LinkedHashMap<>(signer.getPresignedPostFormData(policy));
      fields.put("key", objectKey);
      fields.put("Content-Type", contentType);
      return new UploadForm(uploadUrl, fields, expiry.toInstant());
    } catch (Exception e) {
      throw new IllegalStateException("Could not sign upload", e);
    }
  }

  @Override
  public Optional<StoredObject> stat(String objectKey) {
    try {
      var stat = internal.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
      return Optional.of(new StoredObject(stat.size(), stat.contentType()));
    } catch (ErrorResponseException e) {
      if ("NoSuchKey".equals(e.errorResponse().code())) return Optional.empty();
      throw new IllegalStateException("Storage error", e);
    } catch (Exception e) {
      throw new IllegalStateException("Storage error", e);
    }
  }

  @Override
  public InputStream open(String objectKey) {
    try {
      return internal.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (Exception e) {
      throw new IllegalStateException("Storage error", e);
    }
  }

  @Override
  public void put(String objectKey, byte[] content, String contentType) {
    try {
      internal.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(new ByteArrayInputStream(content), content.length, -1)
          .contentType(contentType).build());
    } catch (Exception e) {
      throw new IllegalStateException("Storage error", e);
    }
  }

  @Override
  public String presignDownload(String objectKey, Duration ttl, String fileName) {
    try {
      return signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(bucket).object(objectKey)
          .expiry((int) Math.max(1, ttl.toSeconds())).extraQueryParams(Map.of("response-content-disposition", "attachment; filename=\"" + fileName + "\"")).build());
    } catch (Exception e) {
      throw new IllegalStateException("Could not sign download", e);
    }
  }

}
