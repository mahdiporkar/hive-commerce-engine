package io.hivecommerce.seller.application;

import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.seller.application.published.SellerDirectory;
import io.hivecommerce.seller.domain.Seller;
import io.hivecommerce.seller.domain.SellerRepository;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.store.application.published.StoreDirectory;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Seller onboarding and administration. Any signed-in user may apply; moderators verify and approve (Hive roles, Layer 1);
 * memberships live only in Commerce, so a new seller needs no Hive administration (ADR-0016, owner requirement R4).
 */
@Service
public class SellerService {
  public record SellerView(UUID sellerId, String kind, String status, Map<String, String> displayName, List<MemberView> members, long revision) {
    static SellerView of(Seller s) {
      return new SellerView(s.id(), s.kind().name(), s.status().name(), s.displayName().values(),
          s.memberships().stream().map(m -> new MemberView(m.actorId(), m.role().name(), m.status().name(), m.capabilities().stream().sorted().toList())).toList(), s.revision());
    }
  }

  public record MemberView(UUID actorId, String role, String status, List<String> capabilities) {}

  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final SellerRepository sellers;
  private final SellerDirectory directory;
  private final StoreDirectory stores;
  private final Clock clock;
  private final boolean onboardingEnabled;

  public SellerService(DeploymentScope deployment, TenantTransactions transactions, SellerRepository sellers, SellerDirectory directory, StoreDirectory stores, Clock clock,
      @Value("${commerce.capabilities.seller-onboarding:false}") boolean onboardingEnabled) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.sellers = sellers;
    this.directory = directory;
    this.stores = stores;
    this.clock = clock;
    this.onboardingEnabled = onboardingEnabled;
  }

  public SellerView apply(UserContext user, Map<String, String> displayName) {
    UUID actor = user.requireActor();
    if (displayName == null) throw DomainValidationException.of("displayName", "is required");
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      if (!onboardingEnabled || !stores.settings(scope).marketplace()) throw new DomainRuleException("MARKETPLACE_DISABLED", "This store does not accept sellers");
      var seller = Seller.apply(Ids.newId(), scope, actor, new LocalizedText(displayName), clock.instant());
      sellers.insert(seller);
      sellers.appendEvents(scope, seller.pullEvents());
      return SellerView.of(seller);
    });
  }

  public List<SellerDirectory.MembershipSummary> myMemberships(UserContext user) {
    UUID actor = user.requireActor();
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> directory.membershipsOf(scope, actor));
  }

  public SellerView grantStaff(UserContext user, UUID sellerId, UUID staffActor, Set<String> capabilities) {
    UUID actor = user.requireActor();
    if (staffActor == null) throw DomainValidationException.of("actorId", "is required");
    return memberChange(actor, sellerId, s -> s.grantMembership(staffActor, Seller.Role.STAFF, capabilities == null ? Set.of() : capabilities, clock.instant()));
  }

  public SellerView revokeStaff(UserContext user, UUID sellerId, UUID staffActor) {
    return memberChange(user.requireActor(), sellerId, s -> s.revokeMembership(staffActor, clock.instant()));
  }

  private SellerView memberChange(UUID actor, UUID sellerId, java.util.function.Consumer<Seller> change) {
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var seller = sellers.findById(scope, sellerId).filter(s -> s.allows(actor, "staff.manage")).orElseThrow(() -> HiveException.notFound("Seller not found"));
      change.accept(seller);
      sellers.update(seller);
      sellers.appendEvents(scope, seller.pullEvents());
      return SellerView.of(sellers.findById(scope, sellerId).orElseThrow());
    });
  }

  // ---- operators (Layer 1: commerce.api.sellers:*)

  public List<SellerView> list(UserContext user, String status) {
    operator(user);
    TenantScope scope = deployment.scope();
    Seller.Status parsed;
    try {
      parsed = Seller.Status.valueOf(status == null ? "APPLIED" : status);
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("status", "unknown seller status");
    }
    return transactions.inScope(scope, () -> sellers.findByStatus(scope, parsed).stream().map(SellerView::of).toList());
  }

  public SellerView verify(UserContext user, UUID id) { return decide(user, id, s -> s.verify(clock.instant())); }

  public SellerView approve(UserContext user, UUID id) { return decide(user, id, s -> s.approve(clock.instant())); }

  public SellerView suspend(UserContext user, UUID id) { return decide(user, id, s -> s.suspend(clock.instant())); }

  public SellerView reinstate(UserContext user, UUID id) { return decide(user, id, s -> s.reinstate(clock.instant())); }

  private SellerView decide(UserContext user, UUID id, java.util.function.Consumer<Seller> change) {
    operator(user);
    TenantScope scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var seller = sellers.findById(scope, id).orElseThrow(() -> HiveException.notFound("Seller not found"));
      change.accept(seller);
      sellers.update(seller);
      sellers.appendEvents(scope, seller.pullEvents());
      return SellerView.of(sellers.findById(scope, id).orElseThrow());
    });
  }

  private void operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
  }
}
