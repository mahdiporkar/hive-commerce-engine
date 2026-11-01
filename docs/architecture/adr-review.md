# ADR architecture review (2026-11-02)

Final review of the Phase 0 ADRs before acceptance. ADR-0004 was approved by the owner on 2026-11-02 with five requirements (R1–R5, listed under ADR-0004 below). Every change that this review recommends has been applied to the ADR text, and those ADRs are then marked `accepted`. A new ADR-0016 records the Layer-2 authorization model that R2 and R3 require.

Reversibility scale: **Low** = changeable in a sprint, local to code. **Medium** = needs a data migration or coordinated client releases. **High** = changes persisted data semantics, external contracts, or many deployments; plan as if permanent.

## Matrix

| ADR | Proposed decision | Alternatives considered | Benefits | Risks and trade-offs | Recommendation | Hard to reverse? |
|---|---|---|---|---|---|---|
| 0001 Record decisions; pin Hive | ADRs for every decision; engine pinned to Hive commit `a75871a` | Track `platform-v1` HEAD; vendored copy of Hive | Reproducible builds; Hive changes are explicit | Manual bumps; falling behind Hive security fixes | **Accept.** Add: review the pin at least once per engine minor release | Low |
| 0002 Modular monolith, module + schema per context | Maven module per context; PostgreSQL schema per context; JDBC; ArchUnit | Microservices; single schema; JPA; Spring Modulith | Compile-time isolation; clean extraction path; one deployable | More modules and migrations; cross-context reporting needs projections; JDBC boilerplate | **Accept with changes:** one runtime DataSource and role (no per-context pools); an outbox table **per context schema**; schema isolation enforced by ArchUnit plus a SQL schema-scan test; one Flyway history per schema | Medium (schema layout); Low (JDBC per context, behind repository ports) |
| 0003 One Hive install per client | Each client runs its own Hive | Shared Hive with prefixed keys; Hive change to scope keys per app | Isolation; identical vocabulary; independent Hive upgrades | About 5 extra containers per client; no single sign-on across clients | **Accept** | Medium: users and grants would have to be migrated between installs |
| 0004 Participant access model | Customers and sellers on `HYBRID` + Commerce Layer 2; operators `AUTHENTICATED` | Manual `SECURITY_ADMIN` assignment per seller; provisioning credential in runtime; Hive feature | No manual admin per seller (R4); Hive keeps authn + operator authz (R1) | Participant endpoints rely on Commerce enforcement; a missing check is a hole | **Accepted by owner** with R1–R5; mitigations made mandatory (deny-by-default filter, registry test, isolation tests) | Medium: moving participants to `AUTHENTICATED` later needs a Hive feature, not a Commerce rewrite |
| 0005 Identity propagation, trust boundary | Private network; JWT validated (iss, aud, exp, sig); `X-Hive-User-Id` only with a valid JWT; `(iss, sub)` binding | Trust headers only (network); mTLS BFF→engine (deferred by Hive) | Defense in depth; forged headers fail | First-seen binding trusts the first BFF-delivered pair; JWKS outage = 401s | **Accept.** Add: JWKS caching with bounded staleness; audience `commerce-engine` via an IdP audience mapper | Low |
| 0006 Vocabulary and route conventions | Route `/commerce`; path namespaces by population; resource-only module `commerce-api` | Per-endpoint resources; one module per area | Small, stable vocabulary; test-enforced conventions | Resource keys are referenced by grants, so renames need new manifest versions | **Accept** (verified against Hive in Phase 0) | Medium: grants and manifests reference the keys |
| 0007 Payments | Port + verify on return URL + signed-webhook ingress + inbox + idempotency keys | Webhook-only; redirect-trust (rejected); BFF route for webhooks (impossible: CSRF) | Two independent confirmation paths converge idempotently | PSPs without webhooks; PSPs that auto-reverse unverified payments | **Accept with changes:** add a **verification sweeper** for attempts stuck in `PAYMENT_INITIATED`, daily **PSP reconciliation**, and an idempotency key on refunds | High: payment records and provider contracts |
| 0008 Digital delivery | Pre-signed upload + finalize; entitlement check → JSON signed URL; private buckets | Stream through engine (exceeds BFF limits); public CDN with obscure keys (rejected) | Large files bypass BFF; every grant checked and logged | Signed URL shareable within its TTL; storage must be reachable by browsers | **Accept with changes:** TTL ≤ 300 s (configurable down to 60 s); grant tied to one content version; download count is consumed at grant time | Medium |
| 0009 Tenant, store, channel | Commerce tenant = Hive tenant; one store per deployment in v1; RLS later | Store from host header now; schema per tenant | Simple v1; SaaS-ready columns | RLS added late is easy to get wrong | **Accept with change:** RLS **from Phase 1**, with `FORCE ROW LEVEL SECURITY`, separate owner (migration) and runtime roles, and `SET LOCAL` scope per transaction | High: tenant scoping is in every table and query |
| 0010 Catalog attributes | JSONB values + immutable schema versions + typed projection | EAV; table per product type; search engine as primary store | Configuration-only industries (Scenario D) | Validation in the app (not DB constraints); projection lag | **Accept** | High: the attribute storage format persists in data |
| 0011 Money and ledger | BigDecimal + ISO-4217; append-only double-entry ledger; derived balances | Single-entry balances; floats/long minor units | Auditable, provably balanced money | More writes; reconciliation job needed | **Accept** | High: financial history is immutable by design |
| 0012 Distribution and CI | GHCR image by digest; SPI + client on GitHub Packages; MFE bundles with SRI; Hive starter from pinned commit | Git submodules; copied source; public Maven Central | No forks; exact pins; clients rebuild on their schedule | GitHub Packages needs tokens even for reads; SNAPSHOT starter version collides with other local builds | **Accept with change:** rewrite the starter version to `0.1.0-a75871a` in the build copy, so the installed artifact is unique per Hive commit; B and C never depend on the Hive starter (SPI has no Hive dependency) | Medium |
| 0013 Guest cart | Random cart token; first-party cookie; sent as `?cart=`; merge on login | Server-side only for signed-in users; token in POST body for every call | Works within BFF header allowlist | Query strings can leak into logs and history | **Accept with change:** the engine never logs query strings; token rotates on login; TTL 7 days. Revisit a POST-body variant in Phase 2 | Low |
| 0014 Client configuration | Versioned JSON Schema, fail-fast, secrets from env | Hive feature flags for capabilities; free-form properties | Invalid configuration never starts | Schema evolution must follow SemVer | **Accept** | Low |
| 0015 DDD model | §6.1 hypothesis adopted with 9 changes | Keep hypothesis verbatim | Grounded in event storming | Desk session without domain experts | **Accept**; stakeholder workshop before Phase 2 | Medium (aggregate boundaries shape tables) |
| 0016 Layer-2 authorization model (new) | Relationship-style policy port (`subject, relation, object`) evaluated in Commerce over its own tables; OpenFGA-compatible vocabulary; no writes to Hive's graph | Separate OpenFGA store for Commerce now; project data into Hive's OpenFGA | Satisfies R2, R3, R4 without a second source of truth | Two authorization engines to understand | **Accept** | Low (behind a port; an OpenFGA-backed implementation can replace the SQL one) |

## Focus areas

### JDBC versus JPA
**Recommendation: Spring JDBC (`JdbcClient`) with explicit row ↔ aggregate mappers.**
- *For JDBC:* annotation-free domain classes (the §6.1 rule "no JPA annotations if feasible"); aggregate boundaries map one-to-one to explicit SQL; optimistic locking is visible (`WHERE revision = ?`); the critical statements in this domain are conditional updates (stock reservation), append-only inserts (ledger) and JSONB, which JPA handles poorly or hides; no lazy-loading surprises across aggregates; no hidden flushes inside the "one aggregate per transaction" rule.
- *Against JDBC:* more mapping code (about 30–60 lines per aggregate) and no automatic dirty checking.
- *Spring Data JDBC* was the closest alternative (aggregate-oriented), but it needs annotations or separate persistence entities, and its JSONB and conditional-update support still ends in custom SQL.
- *Reversibility:* Low. Repositories are ports in `domain`, so one context can switch technology without touching others.

### Database schemas per bounded context
**Recommendation: keep one schema per context, in one database, with one runtime DataSource.**
- Per-context DB roles with separate pools were dropped: N pools for one process add little security, because all contexts share a process and a codebase.
- Isolation is enforced instead by (1) ArchUnit: repositories of context X reference only X's schema constant; (2) a build test that scans each module's SQL for foreign schema names; (3) no cross-schema foreign keys.
- Each context's outbox lives in its own schema, so a context transaction touches exactly one schema. The dispatcher (in `commerce-platform`) reads all outboxes.
- Flyway runs one history per schema (`<schema>.flyway_schema_history`), so contexts migrate independently and expand → migrate → contract applies per context.

### Transaction boundaries
- One aggregate per transaction, with its outbox insert in the same transaction.
- Cross-aggregate consistency is eventual, through the outbox dispatcher (in process, `FOR UPDATE SKIP LOCKED`, at-least-once) and idempotent consumers (`processed_event`).
- The single synchronous exception is the **CheckoutProcess** saga: each step is its own transaction keyed by `CheckoutAttempt`, with explicit compensation. Stock correctness rests on the conditional update in `StockItem.reserve`, not on saga ordering.
- `PaymentCaptured → OrderPaid` is eventual. The verify endpoint returns the payment result immediately, and the storefront polls the order status (normally milliseconds behind).

### Multi-tenant isolation
Four layers, each tested:
1. **Scope source:** tenant from the validated identity (Hive tenant) and store from deployment config, never from request input. A mismatch gives 403.
2. **Application:** every repository method takes `TenantScope`; there are no unscoped finders (ArchUnit rule).
3. **Database:** RLS on every tenant table (`USING (tenant_id = current_setting('app.tenant_id') AND store_id = current_setting('app.store_id'))`), `FORCE ROW LEVEL SECURITY`; the runtime role owns nothing; migrations run as a separate owner role. Scope is set with `SET LOCAL` at transaction start. A missing setting raises an error, so the query fails closed.
4. **Tests:** cross-tenant reads with guessed ids return 404, and a query with no scope set fails (Phase 1).

### Payment and refund consistency
- Idempotency key per checkout attempt and per refund request; unique `(provider, provider_tx_id)`; webhook inbox unique `(provider, event_id)`.
- Refunds are entities inside `Payment`, so `Σ refunds ≤ captured` is enforced in one aggregate under optimistic locking.
- Finance posts reversing journals and never edits. Payout uniqueness is per settlement, so a refund after payout makes the seller balance negative and is netted in the next settlement; it never creates a second payout.
- Added safety nets: a verification sweeper (for PSPs that need an explicit verify, or whose webhooks are missing), daily reconciliation against PSP reports, and the `PAID_UNFULFILLABLE` path for a capture after reservation expiry.

### Digital entitlement security
- Entitlements are created only by the `OrderPaid` consumer.
- Every download checks ownership (customer = entitlement owner, same tenant and store), status, revocation and limit, then issues one signed GET for one content version with TTL ≤ 300 s, and logs the grant.
- Asset ids are 128-bit random; not-yours and not-found both return 404; paid object keys never leave the engine except inside a signed URL.
- Residual risk, stated plainly: a signed URL can be shared for its lifetime, and delivered content can be copied. No DRM is claimed.

### Hive / OpenFGA integration (R1–R3)
- **R1:** Hive remains the only authentication point (OIDC, session, vault) and the only authority for platform and operator authorization (resource actions checked at the BFF, backed by OpenFGA).
- **R2:** Commerce enforces seller, store, tenant and ownership rules in application services and queries (ADR-0016) — see the R2 bullet in ADR-0016.
- **R3:** Commerce never writes to Hive's database or OpenFGA store and does not call Hive's internal APIs. It consumes Hive only through the documented route-operation contract. ADR-0016's policy port uses OpenFGA-style relationship semantics (`user:<id>` `owner` `seller:<id>`), so the same rules can later run in an OpenFGA store — either a dedicated Commerce store or a future Hive delegated-authorization feature — without changing callers.
- **R4:** Seller approval creates a Commerce membership; no Hive administration is needed.
- **R5:** The Phase 1 suite includes allowed/denied operator tests through the real BFF, forged-identity tests, tenant-mismatch tests and RLS isolation tests. The required list in `security.md` gates every later phase.

### Cross-repository dependency management
- A publishes, per SemVer release: an engine image (by digest), `commerce-spi`, `commerce-api-client` and the admin MFE bundles.
- B and C pin exact versions, build `FROM` the engine digest and add extension JARs plus configuration. No source is shared.
- Contract tests (OpenAPI diff and SPI binary compatibility) run in A's CI and in B's and C's upgrade PRs.
- Only A depends on Hive artifacts (the starter, built from the pinned commit). B and C depend on Hive only at runtime (their Hive install) and through manifests rendered from A's templates.
- Engine releases never auto-deploy.

## Hard-to-reverse decisions to watch

ADR-0009 (tenant scoping and RLS), 0010 (attribute storage), 0011 (ledger), 0007 (payment records) and 0003 (one Hive install per client). All are accepted, but changing any of them later needs a migration plan and an ADR.
