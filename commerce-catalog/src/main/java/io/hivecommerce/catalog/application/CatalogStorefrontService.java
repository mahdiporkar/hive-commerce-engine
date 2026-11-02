package io.hivecommerce.catalog.application;

import io.hivecommerce.catalog.application.published.CatalogQueries;
import io.hivecommerce.catalog.application.published.CatalogQueries.CategoryNode;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import java.util.List;
import org.springframework.stereotype.Service;

/** Public (anonymous-safe) catalog reads for storefronts. Only active, non-sensitive data is returned. */
@Service
public class CatalogStorefrontService {
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;
  private final CatalogQueries queries;

  public CatalogStorefrontService(DeploymentScope deployment, TenantTransactions transactions, CatalogQueries queries) {
    this.deployment = deployment;
    this.transactions = transactions;
    this.queries = queries;
  }

  /** The active category tree, flat and ordered by path; clients build the tree from parent ids. */
  public List<CategoryNode> categories(UserContext user) {
    return transactions.inScope(deployment.scope(), () -> queries.activeCategories(deployment.scope()));
  }
}
