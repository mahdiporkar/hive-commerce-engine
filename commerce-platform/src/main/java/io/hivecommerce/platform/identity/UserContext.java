package io.hivecommerce.platform.identity;

import io.hiveplatform.spring.HiveException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Who is calling, in Commerce terms. Built once per request by {@link HiveIdentityFilter} from the BFF-forwarded
 * identity (validated JWT + {@code X-Hive-User-Id}); application code never reads Hive headers or tokens itself.
 *
 * @param actorId Hive canonical user id; {@code null} for anonymous callers
 * @param tenantId Hive tenant of the identity; {@code null} for anonymous callers (the deployment tenant applies)
 * @param routeKey Hive route that delivered the request ({@code X-Hive-Route}); informational for anonymous calls
 * @param operationKey Hive route operation ({@code X-Hive-Operation})
 */
public record UserContext(UUID actorId, String tenantId, String issuer, String subject, String correlationId, String routeKey, String operationKey) {

  public static UserContext anonymous(String correlationId, String routeKey, String operationKey) {
    return new UserContext(null, null, null, null, correlationId, routeKey, operationKey);
  }

  public boolean authenticated() { return actorId != null; }

  /** Participant and operator use cases call this first (ADR-0004: Commerce rejects anonymous HYBRID callers). */
  public UUID requireActor() {
    if (actorId == null) throw new HiveException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Sign in to continue");
    return actorId;
  }
}
