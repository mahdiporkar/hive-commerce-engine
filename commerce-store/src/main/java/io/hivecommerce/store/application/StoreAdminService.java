package io.hivecommerce.store.application;

import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.store.application.published.StoreDirectory;
import io.hivecommerce.store.domain.CommerceMode;
import io.hivecommerce.store.domain.event.StoreEvent;
import io.hivecommerce.store.domain.StoreRepository;
import io.hivecommerce.store.infrastructure.StoreOutbox;
import io.hiveplatform.spring.HiveException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Operator store settings. Upgrading SINGLE_VENDOR to MULTI_VENDOR or HYBRID keeps all data (Scenario E). */
@Service
public class StoreAdminService {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final StoreRepository stores;
  private final StoreDirectory directory;
  private final StoreDirectory.ExternalSellers externalSellers;
  private final StoreOutbox outbox;
  private final Clock clock;

  public StoreAdminService(DeploymentScope deployment, TenantTransactions transactions, StoreRepository stores, StoreDirectory directory,
      StoreDirectory.ExternalSellers externalSellers, StoreOutbox outbox, Clock clock) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.stores = stores;
    this.directory = directory;
    this.externalSellers = externalSellers;
    this.outbox = outbox;
    this.clock = clock;
  }

  public StoreDirectory.StoreSettings settings(UserContext user) {
    operator(user);
    return transactions.inScope(deployment.scope(), () -> directory.settings(deployment.scope()));
  }

  public StoreDirectory.StoreSettings changeMode(UserContext user, String mode) {
    operator(user);
    CommerceMode target;
    try {
      target = CommerceMode.valueOf(String.valueOf(mode));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.of("mode", "must be one of " + List.of(CommerceMode.values()));
    }
    var scope = deployment.scope();
    return transactions.inScope(scope, () -> {
      var store = stores.find(scope).orElseThrow();
      var from = store.mode();
      store.changeMode(target, externalSellers.anyActive(scope));
      if (from != target) {
        stores.update(store);
        outbox.append(scope, List.of(new StoreEvent("CommerceModeChanged", scope.storeId().toString(), Map.of("from", from.name(), "to", target.name()), clock.instant())));
      }
      return directory.settings(scope);
    });
  }

  private void operator(UserContext user) {
    user.requireActor();
    if (!deployment.tenantId().equals(user.tenantId())) throw HiveException.forbidden("This identity belongs to another tenant");
  }
}
