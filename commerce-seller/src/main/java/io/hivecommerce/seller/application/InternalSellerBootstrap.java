package io.hivecommerce.seller.application;

import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.seller.domain.Seller;
import io.hivecommerce.seller.domain.SellerRepository;
import io.hivecommerce.shared.LocalizedText;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Ensures the store's internal seller exists (idempotent; deterministic id), in every commerce mode. */
@Component
class InternalSellerBootstrap implements ApplicationRunner {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final SellerRepository sellers;

  InternalSellerBootstrap(DeploymentScope deployment, TenantTransactions transactions, SellerRepository sellers) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.sellers = sellers;
  }

  @Override
  public void run(ApplicationArguments args) {
    transactions.inScope(deployment.scope(), () -> {
      sellers.insertIfAbsent(Seller.internal(deployment.scope(), LocalizedText.of("en", "Store")));
      return null;
    });
  }
}
