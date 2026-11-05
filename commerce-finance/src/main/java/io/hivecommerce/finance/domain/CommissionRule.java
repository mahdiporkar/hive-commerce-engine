package io.hivecommerce.finance.domain;

import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A configured commission rate (never a constant, master prompt §14). Precedence: PRODUCT > SELLER > CATEGORY > STORE. */
public record CommissionRule(UUID id, TenantScope scope, Level level, UUID targetId, BigDecimal ratePercent, Instant effectiveFrom, Instant effectiveTo) {
  public enum Level { PRODUCT, SELLER, CATEGORY, STORE }

  public CommissionRule {
    if (level == null) throw DomainValidationException.of("level", "PRODUCT, SELLER, CATEGORY or STORE");
    if ((level == Level.STORE) != (targetId == null)) throw DomainValidationException.of("targetId", "required except for STORE rules");
    if (ratePercent == null || ratePercent.signum() < 0 || ratePercent.compareTo(new BigDecimal("100")) > 0) throw DomainValidationException.of("ratePercent", "0 to 100");
    if (effectiveTo != null && effectiveFrom != null && !effectiveTo.isAfter(effectiveFrom)) throw DomainValidationException.of("effectiveTo", "must be after effectiveFrom");
  }

  public boolean activeAt(Instant at) {
    return (effectiveFrom == null || !at.isBefore(effectiveFrom)) && (effectiveTo == null || at.isBefore(effectiveTo));
  }
}
