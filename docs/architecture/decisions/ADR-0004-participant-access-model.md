# ADR-0004 — Participant access model (Gaps 1 and 2)

Status: accepted · Date: 2026-11-01 · Accepted by the owner 2026-11-02 with requirements R1–R5 below

## Context (verified at a75871a)
- First login creates a user with no role or group (`IdentityRegistry.sync`). The OpenFGA model has no wildcard subject, and there is no IdP claim mapping. Only `SECURITY_ADMIN` assigns roles, and delegated administration is deferred.
- The BFF forwards `X-Hive-User-Id` and, with `FORWARD_TOKEN`, the user's bearer token for **every** signed-in call, including `HYBRID` operations (`RuntimeProxy.java:126,158`).
- The commerce runtime must never hold the provisioning credential, which is a full machine administrator.

## Decision
Split users into two populations:

| Population | Size / churn | Layer 1 (Hive, BFF) | Layer 2 (Commerce) |
|---|---|---|---|
| **Participants**: customers, seller owners, seller staff | open enrollment, high churn | operations are `HYBRID` | Commerce requires a validated identity (otherwise 401 `AUTHENTICATION_REQUIRED`) and enforces ownership and seller membership on every call and every query |
| **Operators**: marketplace admins, catalog managers, moderators, finance | closed, few people | operations are `AUTHENTICATED` with `commerce.api.*:action`; roles granted by a `SECURITY_ADMIN` in the console | Commerce additionally checks store administration scope |

Seller approval is therefore a pure Commerce workflow: it creates an active `seller_membership` and needs no Hive call. Customers need no Hive role at all.

## Owner requirements (2026-11-02)
R1 Hive remains responsible for authentication and platform-level authorization. R2 Commerce enforces seller, store, tenant, resource ownership and domain-specific permissions on the backend. R3 Authorization remains compatible with Hive's existing OpenFGA architecture. R4 Seller onboarding and approval require no manual security administration per seller. R5 All security-sensitive operations have automated authorization and tenant-isolation tests. How each is met: [ADR-0016](ADR-0016-layer2-authorization-model.md).

## Alternatives
1. **Seller operations `AUTHENTICATED`, with role `seller` assigned by a human `SECURITY_ADMIN` after approval** (Commerce shows a "pending Hive assignment" queue). This keeps a BFF gate for sellers but adds a manual step per seller. Reasonable for gemstone (few vetted sellers), painful for aidastoor.
2. **Onboarding job holding the provisioning credential.** Rejected (prompt rule, blast radius).
3. **Propose a Hive feature**: a default role on first login, or application-scoped delegated role assignment for a named service client (compare the uncommitted `ScopedRuntime` work in the local Hive checkout). Recommended as a follow-up Hive ADR. It would let participant operations move to `AUTHENTICATED` later without changing Commerce's Layer 2.

## Consequences
- Anonymous callers can reach participant endpoints through the BFF, so Commerce must reject them. Mitigations: a single `RequireParticipant` filter; a deny-by-default registry test (every `HYBRID` operation not explicitly marked anonymous-capable must answer 401 to anonymous callers); and the security tests in Section 19.
- CSRF still applies to every unsafe participant call (at the BFF), and identity headers still cannot be forged through the BFF.
- Layer 1 remains meaningful for every privileged (operator) action.
