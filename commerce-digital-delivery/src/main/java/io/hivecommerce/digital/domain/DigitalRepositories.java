package io.hivecommerce.digital.domain;

import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DigitalRepositories {
  private DigitalRepositories() {}

  public interface Assets {
    void insert(DigitalAsset asset);

    void update(DigitalAsset asset);

    Optional<DigitalAsset> findById(TenantScope scope, String id);

    List<DigitalAsset> findByProduct(TenantScope scope, UUID productId);

    List<DigitalAsset> findBySeller(TenantScope scope, UUID sellerId);
  }

  public interface Uploads {
    void insert(UploadSession upload);

    void update(UploadSession upload);

    Optional<UploadSession> findById(TenantScope scope, String id);
  }

  public interface Entitlements {
    /** Inserts unless an entitlement for the same order line and asset exists (event redelivery). */
    boolean insertIfAbsent(Entitlement entitlement);

    void update(Entitlement entitlement);

    Optional<Entitlement> findById(TenantScope scope, UUID id);

    List<Entitlement> findByCustomer(TenantScope scope, UUID customerId);

    List<Entitlement> findByOrderLine(TenantScope scope, UUID orderLineId);

    void logAccess(Entitlement entitlement, int version, java.time.Instant expiresAt, String delivery);
  }
}
