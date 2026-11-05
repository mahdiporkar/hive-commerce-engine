package io.hivecommerce.finance.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.UUID;

/**
 * Money paid to a seller for one settlement (aggregate root). Invariants: at most one payout per settlement (unique
 * index), so a refund can never produce a second payout; REQUESTED → APPROVED → PAID, or REQUESTED → REJECTED; approved once.
 */
public final class Payout {
  public enum Status { REQUESTED, APPROVED, PAID, REJECTED }

  private final UUID id;
  private final TenantScope scope;
  private final UUID sellerId;
  private final UUID settlementId;
  private final Money amount;
  private Status status;
  private String reference;
  private final long revision;

  public Payout(UUID id, TenantScope scope, UUID sellerId, UUID settlementId, Money amount, Status status, String reference, long revision) {
    this.id = id;
    this.scope = scope;
    this.sellerId = sellerId;
    this.settlementId = settlementId;
    this.amount = amount;
    this.status = status;
    this.reference = reference;
    this.revision = revision;
  }

  public static Payout request(UUID id, TenantScope scope, UUID sellerId, UUID settlementId, Money amount) {
    if (!amount.isPositive()) throw new DomainRuleException("NO_AVAILABLE_BALANCE", "There is nothing to pay out");
    return new Payout(id, scope, sellerId, settlementId, amount, Status.REQUESTED, null, 0);
  }

  public void approve(Money availableBalance) {
    require(Status.REQUESTED, "approve");
    if (amount.amount().compareTo(availableBalance.amount()) > 0) throw new DomainRuleException("PAYOUT_EXCEEDS_BALANCE", "The payout exceeds the seller's available balance");
    status = Status.APPROVED;
  }

  public void reject(String reason) {
    require(Status.REQUESTED, "reject");
    status = Status.REJECTED;
    reference = reason;
  }

  public void markPaid(String bankReference) {
    require(Status.APPROVED, "mark paid");
    status = Status.PAID;
    reference = bankReference;
  }

  private void require(Status expected, String action) {
    if (status != expected) throw new DomainRuleException("PAYOUT_STATUS_INVALID", "Cannot " + action + " a payout in status " + status);
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public UUID sellerId() { return sellerId; }
  public UUID settlementId() { return settlementId; }
  public Money amount() { return amount; }
  public Status status() { return status; }
  public String reference() { return reference; }
  public long revision() { return revision; }
}
