# ADR-0005 — Identity propagation and trust boundary

Status: proposed · Date: 2026-11-01

## Decision
1. **Network.** The engine's API port is reachable only from the client's Hive BFF network. Payment webhooks use a separate port and path (ADR-0007). Hive's target guard already restricts what the BFF calls; the reverse direction is enforced by the deployment (private network, no public route to the API port).
2. **JWT.** A Spring Security resource server validates `Authorization: Bearer`: issuer = the client IdP, audience, expiry, and signature via JWKS. This is the same approach University ERP uses (`ActingContextFilter` runs after Spring Security).
3. **Hive user id.** `X-Hive-User-Id` is accepted only together with a valid JWT. On first sight, Commerce records the binding `(iss, sub) → hiveUserId`. A later request with the same `(iss, sub)` but a different `X-Hive-User-Id` is rejected and audited. A header without a token is rejected with 401. Anonymous requests carry neither.
4. **UserContext** = `{hiveUserId, tenantId (X-Hive-Tenant-Id), issuer, subject, correlationId, routeKey, operationKey}`. `storeId` comes from deployment configuration (ADR-0009), never from the client. Seller scope comes from `seller_membership`.
5. **ProductionGuard** (from the Hive starter) gets Commerce rules. Startup is refused when the fake payment provider is enabled, JWT validation is disabled, the storage bucket is public, webhook secrets are missing, or the database password is shorter than 16 characters.

## Consequences
A forged `X-Hive-User-Id` sent directly to the service fails twice: at the network, and on the missing or mismatched token. A required security test covers this.
