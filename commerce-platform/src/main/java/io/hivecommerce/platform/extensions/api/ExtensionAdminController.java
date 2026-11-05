package io.hivecommerce.platform.extensions.api;

import io.hivecommerce.platform.extensions.ExtensionDocuments;
import io.hivecommerce.platform.extensions.ExtensionRegistry;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.tenancy.TenantTransactions;
import io.hivecommerce.platform.web.HiveOperation;
import io.hivecommerce.shared.Violations;
import io.hiveplatform.spring.HiveException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator access to extension documents (AUTHENTICATED: commerce.api.catalog:read / :update). The extension validates writes. */
@RestController
@RequestMapping("/v1/admin/extensions")
class ExtensionAdminController {
  private final ExtensionRegistry registry;
  private final ExtensionDocuments documents;
  private final DeploymentScope deployment;
  private final TenantTransactions transactions;

  ExtensionAdminController(ExtensionRegistry registry, ExtensionDocuments documents, DeploymentScope deployment, TenantTransactions transactions) {
    this.registry = registry;
    this.documents = documents;
    this.deployment = deployment;
    this.transactions = transactions;
  }

  @GetMapping("/{extensionKey}/{subjectType}/{subjectId}")
  @HiveOperation("admin-extension-get")
  Map<String, Object> get(UserContext user, @PathVariable String extensionKey, @PathVariable String subjectType, @PathVariable String subjectId) {
    user.requireActor();
    return transactions.inScope(deployment.scope(), () -> documents.find(deployment.scope(), extensionKey, subjectType, subjectId)
        .orElseThrow(() -> HiveException.notFound("No such document")));
  }

  @PutMapping("/{extensionKey}/{subjectType}/{subjectId}")
  @HiveOperation("admin-extension-put")
  Map<String, Object> put(UserContext user, @PathVariable String extensionKey, @PathVariable String subjectType, @PathVariable String subjectId,
      @RequestBody Map<String, Object> document) {
    var actor = user.requireActor();
    registry.requireKnown(extensionKey);
    var violations = new Violations();
    registry.dataHandler(extensionKey).orElseThrow().validateData(subjectType, subjectId, document).forEach(i -> violations.add(i.field(), i.message()));
    violations.throwIfAny();
    transactions.inScope(deployment.scope(), () -> {
      documents.put(deployment.scope(), extensionKey, subjectType, subjectId, document, actor);
      return null;
    });
    return document;
  }
}
