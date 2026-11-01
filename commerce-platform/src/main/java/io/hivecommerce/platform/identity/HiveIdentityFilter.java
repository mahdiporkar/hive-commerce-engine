package io.hivecommerce.platform.identity;

import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.web.ErrorResponses;
import io.hiveplatform.spring.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The anticorruption layer between Hive and Commerce (ADR-0005). Runs after Spring Security has validated any bearer
 * token, and turns the request into a {@link UserContext}:
 * <ul>
 *   <li>no token and no Hive identity headers: anonymous (PUBLIC / HYBRID operations);</li>
 *   <li>identity headers without a valid token: rejected (forged or misrouted request);</li>
 *   <li>valid token: requires {@code X-Hive-User-Id}, the deployment's tenant, and a consistent identity binding.</li>
 * </ul>
 */
public class HiveIdentityFilter extends OncePerRequestFilter {
  private final IdentityBindings bindings;
  private final DeploymentScope deployment;
  private final ErrorResponses errors;

  public HiveIdentityFilter(IdentityBindings bindings, DeploymentScope deployment, ErrorResponses errors) {
    this.bindings = bindings;
    this.deployment = deployment;
    this.errors = errors;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String userHeader = request.getHeader(HiveHeaders.USER_ID);
    String tenantHeader = request.getHeader(HiveHeaders.TENANT_ID);
    String route = request.getHeader(HiveHeaders.ROUTE), operation = request.getHeader(HiveHeaders.OPERATION);
    String correlationId = CorrelationId.current();
    var authentication = SecurityContextHolder.getContext().getAuthentication();

    UserContext context;
    if (!(authentication instanceof JwtAuthenticationToken jwt)) {
      if (userHeader != null || tenantHeader != null) {
        errors.write(response, HttpStatus.UNAUTHORIZED, "IDENTITY_UNVERIFIED", "Identity headers require a valid bearer token");
        return;
      }
      context = UserContext.anonymous(correlationId, route, operation);
    } else {
      UUID actor = parseUuid(userHeader);
      if (actor == null || tenantHeader == null) {
        errors.write(response, HttpStatus.UNAUTHORIZED, "IDENTITY_INCOMPLETE", "The Hive user identity is missing");
        return;
      }
      if (!deployment.tenantId().equals(tenantHeader)) {
        errors.write(response, HttpStatus.FORBIDDEN, "TENANT_MISMATCH", "This identity belongs to another tenant");
        return;
      }
      String issuer = String.valueOf(jwt.getToken().getIssuer()), subject = jwt.getToken().getSubject();
      if (subject == null || !bindings.verifyOrRecord(issuer, subject, actor, tenantHeader)) {
        errors.write(response, HttpStatus.UNAUTHORIZED, "IDENTITY_BINDING_MISMATCH", "The forwarded identity does not match the token");
        return;
      }
      context = new UserContext(actor, tenantHeader, issuer, subject, correlationId, route, operation);
    }
    UserContextHolder.set(context);
    try {
      chain.doFilter(request, response);
    } finally {
      UserContextHolder.clear();
    }
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.length() != 36) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
