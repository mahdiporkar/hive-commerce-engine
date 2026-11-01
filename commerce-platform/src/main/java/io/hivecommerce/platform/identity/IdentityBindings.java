package io.hivecommerce.platform.identity;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Binds an external identity {@code (issuer, subject)} to the Hive user id the BFF forwarded the first time it was
 * seen (ADR-0005). Later requests must carry the same Hive user id for that identity; Hive aliases (several external
 * identities for one canonical user) are allowed because the key is the external identity, not the Hive user.
 */
@Component
public class IdentityBindings {
  private static final Logger log = LoggerFactory.getLogger(IdentityBindings.class);
  private final JdbcClient jdbc;

  public IdentityBindings(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** @return true when the identity is bound to {@code hiveUserId} in {@code tenantId} (recording it on first sight) */
  public boolean verifyOrRecord(String issuer, String subject, UUID hiveUserId, String tenantId) {
    jdbc.sql("""
        insert into platform.identity_binding(issuer, subject, hive_user_id, tenant_id)
        values (:issuer, :subject, :user, :tenant) on conflict (issuer, subject) do nothing""")
        .param("issuer", issuer).param("subject", subject).param("user", hiveUserId).param("tenant", tenantId).update();
    var bound = jdbc.sql("select hive_user_id, tenant_id from platform.identity_binding where issuer = :issuer and subject = :subject")
        .param("issuer", issuer).param("subject", subject)
        .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).single();
    boolean matches = hiveUserId.equals(bound[0]) && tenantId.equals(bound[1]);
    if (!matches) {
      log.warn("Identity binding mismatch for issuer={} (forwarded user does not match the recorded binding)", issuer);
      jdbc.sql("insert into platform.security_event(event_type, issuer, subject, details) values ('identity.binding.mismatch', :issuer, :subject, :details)")
          .param("issuer", issuer).param("subject", subject).param("details", "forwarded=" + hiveUserId + " tenant=" + tenantId).update();
    }
    return matches;
  }
}
