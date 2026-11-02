package io.hivecommerce.seller.domain;

import io.hivecommerce.seller.domain.event.SellerEvent;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.LocalizedText;
import io.hivecommerce.shared.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A party that sells in the store (aggregate root). The INTERNAL seller represents the store owner and exists in every
 * mode, so the model is marketplace-shaped from the start (master prompt §7). Invariants: status transitions
 * APPLIED → VERIFIED → ACTIVE ⇄ SUSPENDED (→ CLOSED); an active external seller always has at least one active owner;
 * the internal seller is never suspended or closed. Memberships reference Hive users by id only (ADR-0016): approving a
 * seller needs no Hive administration (owner requirement R4).
 */
public final class Seller {
  public enum Kind { INTERNAL, EXTERNAL }

  public enum Status { APPLIED, VERIFIED, ACTIVE, SUSPENDED, CLOSED }

  public enum Role { OWNER, STAFF }

  public enum MembershipStatus { PENDING, ACTIVE, REVOKED }

  /** Capabilities a membership can hold; owners hold all of them. */
  public static final Set<String> CAPABILITIES = Set.of("offers.manage", "orders.view", "orders.fulfill", "assets.manage", "finance.view", "staff.manage");

  public record Membership(UUID actorId, Role role, Set<String> capabilities, MembershipStatus status) {
    public Membership {
      capabilities = Set.copyOf(capabilities);
    }

    public boolean allows(String capability) {
      return status == MembershipStatus.ACTIVE && (role == Role.OWNER || capabilities.contains(capability));
    }
  }

  private final UUID id;
  private final TenantScope scope;
  private final Kind kind;
  private final LocalizedText displayName;
  private Status status;
  private final List<Membership> memberships;
  private final long revision;
  private final List<DomainEvent> events = new ArrayList<>();

  private Seller(UUID id, TenantScope scope, Kind kind, LocalizedText displayName, Status status, List<Membership> memberships, long revision) {
    this.id = id;
    this.scope = scope;
    this.kind = kind;
    this.displayName = displayName;
    this.status = status;
    this.memberships = new ArrayList<>(memberships);
    this.revision = revision;
  }

  public static UUID internalIdFor(TenantScope scope) {
    return UUID.nameUUIDFromBytes(("hive-commerce-internal-seller:" + scope.tenantId() + ":" + scope.storeId()).getBytes(StandardCharsets.UTF_8));
  }

  public static Seller internal(TenantScope scope, LocalizedText displayName) {
    return new Seller(internalIdFor(scope), scope, Kind.INTERNAL, displayName, Status.ACTIVE, List.of(), 0);
  }

  public static Seller apply(UUID id, TenantScope scope, UUID applicant, LocalizedText displayName, Instant now) {
    if (applicant == null) throw new DomainRuleException("APPLICANT_REQUIRED", "A signed-in applicant is required");
    if (displayName == null) throw io.hivecommerce.shared.DomainValidationException.of("displayName", "is required");
    var seller = new Seller(id, scope, Kind.EXTERNAL, displayName, Status.APPLIED,
        List.of(new Membership(applicant, Role.OWNER, Set.of(), MembershipStatus.PENDING)), 0);
    seller.events.add(new SellerEvent(id, "SellerApplied", now));
    return seller;
  }

  public static Seller restore(UUID id, TenantScope scope, Kind kind, LocalizedText displayName, Status status, List<Membership> memberships, long revision) {
    return new Seller(id, scope, kind, displayName, status, memberships, revision);
  }

  public void verify(Instant now) {
    require(Status.APPLIED, "verify");
    status = Status.VERIFIED;
    events.add(new SellerEvent(id, "SellerVerified", now));
  }

  /** Approval activates the seller and its pending owner memberships. */
  public void approve(Instant now) {
    require(Status.VERIFIED, "approve");
    for (int i = 0; i < memberships.size(); i++) {
      var m = memberships.get(i);
      if (m.role() == Role.OWNER && m.status() == MembershipStatus.PENDING) memberships.set(i, new Membership(m.actorId(), m.role(), m.capabilities(), MembershipStatus.ACTIVE));
    }
    if (activeOwners() == 0) throw new DomainRuleException("SELLER_WITHOUT_OWNER", "A seller needs an active owner");
    status = Status.ACTIVE;
    events.add(new SellerEvent(id, "SellerApproved", now));
  }

  public void suspend(Instant now) {
    if (kind == Kind.INTERNAL) throw new DomainRuleException("INTERNAL_SELLER_PROTECTED", "The store's own seller cannot be suspended");
    require(Status.ACTIVE, "suspend");
    status = Status.SUSPENDED;
    events.add(new SellerEvent(id, "SellerSuspended", now));
  }

  public void reinstate(Instant now) {
    require(Status.SUSPENDED, "reinstate");
    status = Status.ACTIVE;
    events.add(new SellerEvent(id, "SellerReinstated", now));
  }

  public void grantMembership(UUID actorId, Role role, Set<String> capabilities, Instant now) {
    if (kind == Kind.INTERNAL) throw new DomainRuleException("INTERNAL_SELLER_PROTECTED", "Store operators act for the internal seller through Hive roles");
    if (status != Status.ACTIVE) throw new DomainRuleException("SELLER_NOT_ACTIVE", "Memberships can be granted only for active sellers");
    var unknown = capabilities.stream().filter(c -> !CAPABILITIES.contains(c)).toList();
    if (!unknown.isEmpty()) throw io.hivecommerce.shared.DomainValidationException.of("capabilities", "unknown capabilities " + unknown);
    memberships.removeIf(m -> m.actorId().equals(actorId) && m.status() != MembershipStatus.ACTIVE);
    if (membership(actorId).isPresent()) throw new DomainRuleException("ALREADY_MEMBER", "This user is already a member");
    memberships.add(new Membership(actorId, role, role == Role.OWNER ? Set.of() : capabilities, MembershipStatus.ACTIVE));
    events.add(new SellerEvent(id, "MembershipGranted", now));
  }

  public void revokeMembership(UUID actorId, Instant now) {
    var current = membership(actorId).orElseThrow(() -> new DomainRuleException("NOT_A_MEMBER", "This user is not a member"));
    if (current.role() == Role.OWNER && activeOwners() == 1) throw new DomainRuleException("LAST_OWNER", "A seller always keeps at least one owner");
    memberships.replaceAll(m -> m.actorId().equals(actorId) ? new Membership(m.actorId(), m.role(), m.capabilities(), MembershipStatus.REVOKED) : m);
    events.add(new SellerEvent(id, "MembershipRevoked", now));
  }

  /** Layer-2 relation check: may this Hive user act for this seller with the capability (ADR-0016)? */
  public boolean allows(UUID actorId, String capability) {
    return status == Status.ACTIVE && membership(actorId).map(m -> m.allows(capability)).orElse(false);
  }

  public Optional<Membership> membership(UUID actorId) {
    return memberships.stream().filter(m -> m.actorId().equals(actorId) && m.status() == MembershipStatus.ACTIVE).findFirst();
  }

  private long activeOwners() {
    return memberships.stream().filter(m -> m.role() == Role.OWNER && m.status() == MembershipStatus.ACTIVE).count();
  }

  private void require(Status expected, String action) {
    if (status != expected) throw new DomainRuleException("SELLER_STATUS_INVALID", "Cannot " + action + " a seller in status " + status);
  }

  public List<DomainEvent> pullEvents() {
    var copy = List.copyOf(events);
    events.clear();
    return copy;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public Kind kind() { return kind; }
  public LocalizedText displayName() { return displayName; }
  public Status status() { return status; }
  public List<Membership> memberships() { return List.copyOf(memberships); }
  public long revision() { return revision; }
}
