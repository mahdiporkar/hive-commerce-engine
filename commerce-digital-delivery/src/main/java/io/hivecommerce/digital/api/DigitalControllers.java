package io.hivecommerce.digital.api;

import io.hivecommerce.digital.application.DigitalService;
import io.hivecommerce.digital.application.DigitalService.AssetCommand;
import io.hivecommerce.digital.application.DigitalService.AssetView;
import io.hivecommerce.digital.application.DigitalService.Download;
import io.hivecommerce.digital.application.DigitalService.LibraryItem;
import io.hivecommerce.digital.application.DigitalService.PublicPreview;
import io.hivecommerce.digital.application.DigitalService.UploadView;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

final class DigitalControllers {
  private DigitalControllers() {}

  record UploadBody(String contentType, long size, String sha256) {}

  record TextBody(String content) {}

  record PreviewBody(String preview) {}

  record RevokeBody(String reason) {}

  /** Operator asset management for the internal seller (AUTHENTICATED, commerce.api.catalog:update / commerce.api.digital:revoke). */
  @RestController
  @RequestMapping("/v1/admin")
  static class AdminAssets {
    private final DigitalService digital;

    AdminAssets(DigitalService digital) {
      this.digital = digital;
    }

    @PostMapping("/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("admin-asset-create")
    AssetView create(UserContext user, @RequestBody AssetCommand body) { return digital.createStoreAsset(user, body); }

    @PostMapping("/assets/{assetId}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("admin-asset-upload")
    UploadView upload(UserContext user, @PathVariable String assetId, @RequestBody UploadBody body) {
      return digital.requestUpload(user, assetId, digital.operatorAccess(user), body.contentType(), body.size(), body.sha256());
    }

    @PostMapping("/assets/{assetId}/uploads/{uploadId}/finalize")
    @HiveOperation("admin-asset-finalize")
    AssetView finalizeUpload(UserContext user, @PathVariable String assetId, @PathVariable String uploadId) {
      return digital.finalizeUpload(user, assetId, uploadId, digital.operatorAccess(user));
    }

    @PutMapping("/assets/{assetId}/text")
    @HiveOperation("admin-asset-text")
    AssetView text(UserContext user, @PathVariable String assetId, @RequestBody TextBody body) {
      return digital.putText(user, assetId, digital.operatorAccess(user), body.content());
    }

    @PutMapping("/assets/{assetId}/preview")
    @HiveOperation("admin-asset-preview")
    AssetView preview(UserContext user, @PathVariable String assetId, @RequestBody PreviewBody body) {
      return digital.setPreview(user, assetId, digital.operatorAccess(user), body.preview());
    }

    @PostMapping("/entitlements/{entitlementId}/revoke")
    @HiveOperation("admin-entitlement-revoke")
    LibraryItem revoke(UserContext user, @PathVariable UUID entitlementId, @RequestBody RevokeBody body) { return digital.revoke(user, entitlementId, body.reason()); }
  }

  /** Seller workspace assets (HYBRID; assets.manage on the owning seller; others get 404). */
  @RestController
  @RequestMapping("/v1/seller")
  static class SellerAssets {
    private final DigitalService digital;

    SellerAssets(DigitalService digital) {
      this.digital = digital;
    }

    @GetMapping("/sellers/{sellerId}/assets")
    @HiveOperation("seller-assets")
    java.util.List<AssetView> assets(UserContext user, @PathVariable UUID sellerId) { return digital.sellerAssets(user, sellerId); }

    @PostMapping("/sellers/{sellerId}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("seller-asset-create")
    AssetView create(UserContext user, @PathVariable UUID sellerId, @RequestBody AssetCommand body) { return digital.createSellerAsset(user, sellerId, body); }

    @PostMapping("/assets/{assetId}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("seller-asset-upload")
    UploadView upload(UserContext user, @PathVariable String assetId, @RequestBody UploadBody body) {
      return digital.requestUpload(user, assetId, digital.sellerAccess(user), body.contentType(), body.size(), body.sha256());
    }

    @PostMapping("/assets/{assetId}/uploads/{uploadId}/finalize")
    @HiveOperation("seller-asset-finalize")
    AssetView finalizeUpload(UserContext user, @PathVariable String assetId, @PathVariable String uploadId) {
      return digital.finalizeUpload(user, assetId, uploadId, digital.sellerAccess(user));
    }

    @PutMapping("/assets/{assetId}/text")
    @HiveOperation("seller-asset-text")
    AssetView text(UserContext user, @PathVariable String assetId, @RequestBody TextBody body) { return digital.putText(user, assetId, digital.sellerAccess(user), body.content()); }

    @PutMapping("/assets/{assetId}/preview")
    @HiveOperation("seller-asset-preview")
    AssetView preview(UserContext user, @PathVariable String assetId, @RequestBody PreviewBody body) { return digital.setPreview(user, assetId, digital.sellerAccess(user), body.preview()); }
  }

  /** Customer library and downloads (HYBRID: own entitlements only). */
  @RestController
  @RequestMapping("/v1/shop")
  static class ShopLibrary {
    private final DigitalService digital;

    ShopLibrary(DigitalService digital) {
      this.digital = digital;
    }

    @GetMapping("/library")
    @HiveOperation("shop-library")
    List<LibraryItem> library(UserContext user) { return digital.library(user); }

    @PostMapping("/library/{entitlementId}/downloads")
    @HiveOperation("shop-download")
    Download download(UserContext user, @PathVariable UUID entitlementId) { return digital.download(user, entitlementId); }
  }

  /** Public previews (PUBLIC): titles and preview text only. */
  @RestController
  @RequestMapping("/v1/store")
  static class StorePreviews {
    private final DigitalService digital;

    StorePreviews(DigitalService digital) {
      this.digital = digital;
    }

    @GetMapping("/products/{productId}/previews")
    @HiveOperation("store-product-previews")
    List<PublicPreview> previews(UserContext user, @PathVariable UUID productId) { return digital.previews(productId); }
  }
}
