package io.hivecommerce.digital.infrastructure;

import io.hivecommerce.digital.domain.DigitalAsset;
import io.hivecommerce.digital.domain.DigitalRepositories;
import io.hivecommerce.digital.domain.Entitlement;
import io.hivecommerce.digital.domain.UploadSession;
import io.hivecommerce.shared.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

final class JdbcDigitalRepositories {
  private JdbcDigitalRepositories() {}

  @Repository
  static class Assets implements DigitalRepositories.Assets {
    private final JdbcClient jdbc;

    Assets(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public void insert(DigitalAsset a) {
      jdbc.sql("""
          insert into digital.digital_asset(id, tenant_id, store_id, owner_seller_id, product_id, kind, title, download_limit, updates_included, license_usage, preview_text)
          values (:id, :tenant, :store, :seller, :product, :kind, :title, :limit, :updates, :usage, :preview)""")
          .param("id", a.id()).param("tenant", a.scope().tenantId()).param("store", a.scope().storeId()).param("seller", a.ownerSellerId())
          .param("product", a.productId()).param("kind", a.kind().name()).param("title", a.title()).param("limit", a.license().downloadLimit())
          .param("updates", a.license().updatesIncluded()).param("usage", a.license().usage()).param("preview", a.previewText()).update();
    }

    @Override
    public void update(DigitalAsset a) {
      int updated = jdbc.sql("""
          update digital.digital_asset set preview_text = :preview, revision = revision + 1, updated_at = now()
          where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
          .param("preview", a.previewText()).param("id", a.id()).param("tenant", a.scope().tenantId()).param("store", a.scope().storeId())
          .param("revision", a.revision()).update();
      if (updated != 1) throw new OptimisticLockingFailureException("asset " + a.id() + " changed concurrently");
      for (var v : a.versions()) {
        jdbc.sql("""
            insert into digital.content_version(asset_id, tenant_id, store_id, version, object_key, sha256, size_bytes, mime_type, scan_status, created_at)
            values (:asset, :tenant, :store, :version, :key, :sha, :size, :mime, :scan, :created) on conflict (asset_id, version) do nothing""")
            .param("asset", a.id()).param("tenant", a.scope().tenantId()).param("store", a.scope().storeId()).param("version", v.number())
            .param("key", v.objectKey()).param("sha", v.sha256()).param("size", v.size()).param("mime", v.mimeType()).param("scan", v.scanStatus())
            .param("created", Timestamp.from(v.createdAt())).update();
      }
    }

    @Override
    public Optional<DigitalAsset> findById(TenantScope scope, String id) { return select(scope, "id = :value", id).stream().findFirst(); }

    @Override
    public List<DigitalAsset> findByProduct(TenantScope scope, UUID productId) { return select(scope, "product_id = :value", productId); }

    @Override
    public List<DigitalAsset> findBySeller(TenantScope scope, UUID sellerId) { return select(scope, "owner_seller_id = :value", sellerId); }

    private List<DigitalAsset> select(TenantScope scope, String condition, Object value) {
      var rows = jdbc.sql("select id, owner_seller_id, product_id, kind, title, download_limit, updates_included, license_usage, preview_text, revision "
              + "from digital.digital_asset where tenant_id = :tenant and store_id = :store and " + condition + " order by created_at")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
          .query((rs, n) -> new Object[] {rs.getString("id"), rs.getObject("owner_seller_id", UUID.class), rs.getObject("product_id", UUID.class),
              rs.getString("kind"), rs.getString("title"), (Integer) rs.getObject("download_limit"), rs.getBoolean("updates_included"), rs.getString("license_usage"),
              rs.getString("preview_text"), rs.getLong("revision")})
          .list();
      return rows.stream().map(r -> DigitalAsset.restore((String) r[0], scope, (UUID) r[1], (UUID) r[2], DigitalAsset.Kind.valueOf((String) r[3]), (String) r[4],
          new DigitalAsset.LicenseTerms((Integer) r[5], (Boolean) r[6], (String) r[7]), versions(scope, (String) r[0]), (String) r[8], (Long) r[9])).toList();
    }

    private List<DigitalAsset.ContentVersion> versions(TenantScope scope, String assetId) {
      return jdbc.sql("select version, object_key, sha256, size_bytes, mime_type, scan_status, created_at from digital.content_version "
              + "where asset_id = :asset and tenant_id = :tenant and store_id = :store order by version")
          .param("asset", assetId).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> new DigitalAsset.ContentVersion(rs.getInt("version"), rs.getString("object_key"), rs.getString("sha256"), rs.getLong("size_bytes"),
              rs.getString("mime_type"), rs.getString("scan_status"), rs.getTimestamp("created_at").toInstant()))
          .list();
    }
  }

  @Repository
  static class Uploads implements DigitalRepositories.Uploads {
    private final JdbcClient jdbc;

    Uploads(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public void insert(UploadSession u) {
      jdbc.sql("""
          insert into digital.upload_session(id, tenant_id, store_id, asset_id, object_key, declared_size, declared_sha256, declared_mime, expires_at, status)
          values (:id, :tenant, :store, :asset, :key, :size, :sha, :mime, :expires, :status)""")
          .param("id", u.id()).param("tenant", u.scope().tenantId()).param("store", u.scope().storeId()).param("asset", u.assetId()).param("key", u.objectKey())
          .param("size", u.declaredSize()).param("sha", u.declaredSha256()).param("mime", u.declaredMime()).param("expires", Timestamp.from(u.expiresAt()))
          .param("status", u.status().name()).update();
    }

    @Override
    public void update(UploadSession u) {
      jdbc.sql("update digital.upload_session set status = :status, rejection = :rejection, updated_at = now() where id = :id and tenant_id = :tenant and store_id = :store")
          .param("status", u.status().name()).param("rejection", u.rejection()).param("id", u.id()).param("tenant", u.scope().tenantId())
          .param("store", u.scope().storeId()).update();
    }

    @Override
    public Optional<UploadSession> findById(TenantScope scope, String id) {
      return jdbc.sql("select id, asset_id, object_key, declared_size, declared_sha256, declared_mime, expires_at, status, rejection from digital.upload_session "
              + "where id = :id and tenant_id = :tenant and store_id = :store")
          .param("id", id).param("tenant", scope.tenantId()).param("store", scope.storeId())
          .query((rs, n) -> UploadSession.restore(rs.getString("id"), scope, rs.getString("asset_id"), rs.getString("object_key"), rs.getLong("declared_size"),
              rs.getString("declared_sha256"), rs.getString("declared_mime"), rs.getTimestamp("expires_at").toInstant(),
              UploadSession.Status.valueOf(rs.getString("status")), rs.getString("rejection")))
          .optional();
    }
  }

  @Repository
  static class Entitlements implements DigitalRepositories.Entitlements {
    private final JdbcClient jdbc;

    Entitlements(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Entitlement e) {
      return jdbc.sql("""
          insert into digital.entitlement(id, tenant_id, store_id, customer_id, order_id, order_line_id, product_id, asset_id, granted_version, updates_included,
            download_limit, downloads_used, status)
          values (:id, :tenant, :store, :customer, :order, :line, :product, :asset, :version, :updates, :limit, 0, :status)
          on conflict (order_line_id, asset_id) do nothing""")
          .param("id", e.id()).param("tenant", e.scope().tenantId()).param("store", e.scope().storeId()).param("customer", e.customerId())
          .param("order", e.orderId()).param("line", e.orderLineId()).param("product", e.productId()).param("asset", e.assetId())
          .param("version", e.grantedVersion()).param("updates", e.updatesIncluded()).param("limit", e.downloadLimit()).param("status", e.status().name())
          .update() == 1;
    }

    @Override
    public void update(Entitlement e) {
      int updated = jdbc.sql("""
          update digital.entitlement set downloads_used = :used, status = :status, revoked_reason = :reason, revision = revision + 1, updated_at = now()
          where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
          .param("used", e.downloadsUsed()).param("status", e.status().name()).param("reason", e.revokedReason()).param("id", e.id())
          .param("tenant", e.scope().tenantId()).param("store", e.scope().storeId()).param("revision", e.revision()).update();
      if (updated != 1) throw new OptimisticLockingFailureException("entitlement " + e.id() + " changed concurrently");
    }

    @Override
    public Optional<Entitlement> findById(TenantScope scope, UUID id) { return select(scope, "id = :value", id).stream().findFirst(); }

    @Override
    public List<Entitlement> findByCustomer(TenantScope scope, UUID customerId) { return select(scope, "customer_id = :value", customerId); }

    @Override
    public List<Entitlement> findByOrderLine(TenantScope scope, UUID lineId) { return select(scope, "order_line_id = :value", lineId); }

    @Override
    public void logAccess(Entitlement e, int version, Instant expiresAt, String delivery) {
      jdbc.sql("""
          insert into digital.download_access_log(id, tenant_id, store_id, entitlement_id, customer_id, version, delivery, expires_at)
          values (gen_random_uuid(), :tenant, :store, :entitlement, :customer, :version, :delivery, :expires)""")
          .param("tenant", e.scope().tenantId()).param("store", e.scope().storeId()).param("entitlement", e.id()).param("customer", e.customerId())
          .param("version", version).param("delivery", delivery).param("expires", expiresAt == null ? null : Timestamp.from(expiresAt)).update();
    }

    private List<Entitlement> select(TenantScope scope, String condition, UUID value) {
      return jdbc.sql("select id, customer_id, order_id, order_line_id, product_id, asset_id, granted_version, updates_included, download_limit, downloads_used, "
              + "status, revoked_reason, revision from digital.entitlement where tenant_id = :tenant and store_id = :store and " + condition + " order by created_at")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
          .query((rs, n) -> Entitlement.restore(rs.getObject("id", UUID.class), scope, rs.getObject("customer_id", UUID.class), rs.getObject("order_id", UUID.class),
              rs.getObject("order_line_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("asset_id"), rs.getInt("granted_version"),
              rs.getBoolean("updates_included"), (Integer) rs.getObject("download_limit"), rs.getInt("downloads_used"),
              Entitlement.Status.valueOf(rs.getString("status")), rs.getString("revoked_reason"), rs.getLong("revision")))
          .list();
    }
  }
}
