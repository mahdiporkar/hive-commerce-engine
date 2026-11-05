package io.hivecommerce.seller.api;

import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import io.hivecommerce.seller.application.SellerService;
import io.hivecommerce.seller.application.SellerService.SellerView;
import io.hivecommerce.seller.application.published.SellerDirectory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

final class SellerControllers {
  private SellerControllers() {}

  record ApplicationBody(Map<String, String> displayName) {}

  record StaffBody(UUID actorId, Set<String> capabilities) {}

  /** Seller workspace (HYBRID; Commerce checks membership, ADR-0004). */
  @RestController
  @RequestMapping("/v1/seller")
  static class Workspace {
    private final SellerService sellers;

    Workspace(SellerService sellers) {
      this.sellers = sellers;
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("seller-apply")
    SellerView apply(UserContext user, @RequestBody ApplicationBody body) { return sellers.apply(user, body.displayName()); }

    @GetMapping("/me")
    @HiveOperation("seller-me")
    List<SellerDirectory.MembershipSummary> me(UserContext user) { return sellers.myMemberships(user); }

    @PostMapping("/sellers/{sellerId}/staff")
    @HiveOperation("seller-staff-grant")
    SellerView grant(UserContext user, @PathVariable UUID sellerId, @RequestBody StaffBody body) { return sellers.grantStaff(user, sellerId, body.actorId(), body.capabilities()); }

    @DeleteMapping("/sellers/{sellerId}/staff/{actorId}")
    @HiveOperation("seller-staff-revoke")
    SellerView revoke(UserContext user, @PathVariable UUID sellerId, @PathVariable UUID actorId) { return sellers.revokeStaff(user, sellerId, actorId); }
  }

  /** Operator seller administration (AUTHENTICATED: commerce.api.sellers:view / :approve / :suspend). */
  @RestController
  @RequestMapping("/v1/admin")
  static class Admin {
    private final SellerService sellers;

    Admin(SellerService sellers) {
      this.sellers = sellers;
    }

    @GetMapping("/sellers")
    @HiveOperation("admin-sellers")
    List<SellerView> list(UserContext user, @RequestParam(required = false) String status) { return sellers.list(user, status); }

    @PostMapping("/sellers/{sellerId}/verify")
    @HiveOperation("admin-seller-verify")
    SellerView verify(UserContext user, @PathVariable UUID sellerId) { return sellers.verify(user, sellerId); }

    @PostMapping("/sellers/{sellerId}/approve")
    @HiveOperation("admin-seller-approve")
    SellerView approve(UserContext user, @PathVariable UUID sellerId) { return sellers.approve(user, sellerId); }

    @PostMapping("/sellers/{sellerId}/suspend")
    @HiveOperation("admin-seller-suspend")
    SellerView suspend(UserContext user, @PathVariable UUID sellerId) { return sellers.suspend(user, sellerId); }

    @PostMapping("/sellers/{sellerId}/reinstate")
    @HiveOperation("admin-seller-reinstate")
    SellerView reinstate(UserContext user, @PathVariable UUID sellerId) { return sellers.reinstate(user, sellerId); }
  }
}
