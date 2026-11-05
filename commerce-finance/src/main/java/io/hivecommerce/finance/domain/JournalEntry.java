package io.hivecommerce.finance.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A balanced, append-only set of ledger lines (aggregate root, ADR-0011). Invariants: Σ debits = Σ credits in one
 * currency; zero lines are dropped; corrections are new reversing entries. {@code (type, sourceId)} makes posting
 * idempotent. The database repeats these rules with triggers.
 */
public final class JournalEntry {
  public enum Type { CASH_IN, CASH_OUT, ORDER_ALLOCATION, REFUND_ALLOCATION, SETTLEMENT_RELEASE, PAYOUT }

  public record Line(String account, BigDecimal debit, BigDecimal credit) {}

  /** Ledger accounts. Seller accounts carry the seller id; balances are always derived from entries. */
  public static final class Accounts {
    public static final String CASH = "CASH", CLEARING = "CLEARING", COMMISSION_REVENUE = "COMMISSION_REVENUE", TAX_PAYABLE = "TAX_PAYABLE";

    public static String sellerPending(UUID sellerId) { return "SELLER_PENDING:" + sellerId; }

    public static String sellerAvailable(UUID sellerId) { return "SELLER_AVAILABLE:" + sellerId; }

    private Accounts() {}
  }

  public static final class Builder {
    private final List<Line> lines = new ArrayList<>();
    private final java.util.Currency currency;

    private Builder(java.util.Currency currency) {
      this.currency = currency;
    }

    public Builder debit(String account, Money amount) { return add(account, amount, true); }

    public Builder credit(String account, Money amount) { return add(account, amount, false); }

    private Builder add(String account, Money amount, boolean debit) {
      if (!amount.currency().equals(currency)) throw new DomainRuleException("JOURNAL_CURRENCY_MISMATCH", "A journal entry has one currency");
      if (amount.amount().signum() < 0) throw new DomainRuleException("JOURNAL_NEGATIVE_AMOUNT", "Journal amounts must not be negative");
      if (amount.amount().signum() > 0) lines.add(new Line(account, debit ? amount.amount() : BigDecimal.ZERO, debit ? BigDecimal.ZERO : amount.amount()));
      return this;
    }

    public JournalEntry build(UUID id, TenantScope scope, Type type, String sourceId) {
      BigDecimal debits = lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal credits = lines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (lines.isEmpty() || debits.compareTo(credits) != 0) throw new DomainRuleException("JOURNAL_UNBALANCED", "Debits must equal credits");
      return new JournalEntry(id, scope, type, sourceId, currency.getCurrencyCode(), List.copyOf(lines));
    }
  }

  public static Builder in(java.util.Currency currency) { return new Builder(currency); }

  private final UUID id;
  private final TenantScope scope;
  private final Type type;
  private final String sourceId;
  private final String currency;
  private final List<Line> lines;

  private JournalEntry(UUID id, TenantScope scope, Type type, String sourceId, String currency, List<Line> lines) {
    this.id = id;
    this.scope = scope;
    this.type = type;
    this.sourceId = sourceId;
    this.currency = currency;
    this.lines = lines;
  }

  public UUID id() { return id; }
  public TenantScope scope() { return scope; }
  public Type type() { return type; }
  public String sourceId() { return sourceId; }
  public String currency() { return currency; }
  public List<Line> lines() { return lines; }
}
