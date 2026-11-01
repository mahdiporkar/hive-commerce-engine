package io.hivecommerce.store.domain;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import io.hivecommerce.shared.Violations;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One commercial business (aggregate root). Invariants: one default currency; locales non-empty and valid;
 * upgrading SINGLE_VENDOR to MULTI_VENDOR or HYBRID is allowed, downgrading is rejected while external sellers are active.
 */
public final class Store {
  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,79}");
  private static final Pattern LOCALE = Pattern.compile("[a-z]{2}");

  private final TenantScope scope;
  private final String key;
  private CommerceMode mode;
  private final Currency defaultCurrency;
  private final List<String> locales;
  private final long revision;

  private Store(TenantScope scope, String key, CommerceMode mode, Currency defaultCurrency, List<String> locales, long revision) {
    this.scope = scope;
    this.key = key;
    this.mode = mode;
    this.defaultCurrency = defaultCurrency;
    this.locales = List.copyOf(locales);
    this.revision = revision;
  }

  /**
   * The store id is derived from tenant and key, so the deployment knows its row-level-security scope before the first
   * query, and re-running the bootstrap is idempotent.
   */
  public static UUID idFor(String tenantId, String key) {
    return UUID.nameUUIDFromBytes(("hive-commerce-store:" + tenantId + ":" + key).getBytes(StandardCharsets.UTF_8));
  }

  public static Store bootstrap(String tenantId, String key, CommerceMode mode, String currencyCode, List<String> locales) {
    var violations = new Violations();
    if (key == null || !KEY.matcher(key).matches()) violations.add("store.key", "must match " + KEY.pattern());
    if (mode == null) violations.add("store.mode", "is required");
    Currency currency = null;
    try {
      currency = Currency.getInstance(currencyCode);
    } catch (RuntimeException e) {
      violations.add("store.defaultCurrency", "must be an ISO-4217 code");
    }
    if (locales == null || locales.isEmpty() || locales.stream().anyMatch(l -> l == null || !LOCALE.matcher(l).matches())) {
      violations.add("store.locales", "must list ISO-639-1 codes such as fa, ar, en");
    }
    violations.throwIfAny();
    return new Store(new TenantScope(tenantId, idFor(tenantId, key)), key, mode, currency, locales, 0);
  }

  public static Store restore(TenantScope scope, String key, CommerceMode mode, Currency currency, List<String> locales, long revision) {
    return new Store(scope, key, mode, currency, locales, revision);
  }

  public void changeMode(CommerceMode target, boolean externalSellersActive) {
    if (target == mode) return;
    if (target == CommerceMode.SINGLE_VENDOR && externalSellersActive) {
      throw new DomainRuleException("MODE_DOWNGRADE_BLOCKED", "Cannot return to single-vendor mode while external sellers are active");
    }
    mode = target;
  }

  public TenantScope scope() { return scope; }
  public String key() { return key; }
  public CommerceMode mode() { return mode; }
  public Currency defaultCurrency() { return defaultCurrency; }
  public List<String> locales() { return locales; }
  public long revision() { return revision; }
}
