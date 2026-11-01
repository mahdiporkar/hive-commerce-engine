# Phase status

## Phase 0 — Discovery and Architecture (COMPLETE, awaiting approval at the gate)

**Proposed ADRs (all `Status: proposed`). Decide these first:**
1. [ADR-0004](architecture/decisions/ADR-0004-participant-access-model.md): customers and sellers use `HYBRID` operations with Commerce-enforced identity and ownership; only operators are Hive-gated. **Needs an explicit owner decision** (the alternative is manual `SECURITY_ADMIN` role assignment per seller).
2. [ADR-0003](architecture/decisions/ADR-0003-one-hive-install-per-client.md): one Hive installation per client (forced by install-global key uniqueness).
3. [ADR-0015](architecture/decisions/ADR-0015-ddd-model.md): DDD model confirmed from the §6.1 hypothesis with 9 changes (attribute library, SKU scope, UniqueItem as StockItem flag, single trigger for entitlements, Finance inputs, Refund inside Payment, no Customer context in v1, Hive ACL module, capture-after-expiry).
4. [ADR-0002](architecture/decisions/ADR-0002-modular-monolith.md) (revised): one Maven module and one PostgreSQL schema per bounded context, JDBC not JPA, ArchUnit boundary rules.
5. [ADR-0012](architecture/decisions/ADR-0012-distribution-and-ci.md): GHCR image + GitHub Packages; Hive starter built from the pinned commit.
6. ADR-0001, 0005–0011, 0013, 0014: technical defaults.

Implemented:
- DDD model (master prompt v2 §6.1): [event storming](domain/event-storming.md) (desk session, 8 flows, hotspots for a stakeholder workshop), [glossary](domain/glossary.md) (English + proposed Persian), [context map](domain/context-map.md), [aggregate catalogue with invariants and the CheckoutProcess saga](domain/aggregates.md), [domain event catalogue](domain/domain-events.md), [domain model overview](architecture/domain-model.md), [ERD per context](architecture/erd.md), [extension system](architecture/extension-system.md).
- Repository discovery (below), the [verified Hive capability matrix](architecture/hive-integration.md), Section 5 gap findings, 14 ADRs, [overview with context map and sequence diagrams](architecture/overview.md), [security model](architecture/security.md), [route-operation table and resource vocabulary](architecture/route-operations.md), [ERD](architecture/erd.md), [repository boundaries](architecture/repository-boundaries.md), [roadmap and minimum vertical slice](roadmap.md), draft resource manifest `hive/drafts/gemstone/commerce-api/resource-manifest.json`, Hive pin `hive/HIVE_REVISION`, smoke test `tests/smoke/phase0-bff-smoke.mjs`.

Repository discovery (2026-11-01):

| Repo | Branch / SHA | State |
|---|---|---|
| hive-platform | `origin/platform-v1` @ `a75871a64b9f6ab92c762a126e2fc94284a10429` (`0.1.0-dev.0`, Java 21, Spring Boot 3.5.5, Maven, Node ≥ 22, TypeScript, Flyway V1–V8) | existing platform. The local working copy is on `fix/bff-identity-control-internal-http` (`ee7a1c6`) with **uncommitted** `ScopedRuntime*` and `cli/src/register.mjs` changes; left untouched, read from a clean export |
| hive-commerce-engine | remote: no refs (empty) | this local branch `phase-0/architecture` only, not pushed |
| gemstone-commerce | remote: no refs (empty) | untouched |
| aidastoor-commerce | remote: no refs (empty) | untouched |

Reused (from Hive / existing code):
- Hive BFF routing, CSRF, header allowlist, resource catalog and manifest governance, used unchanged.
- University ERP's patterns: JWT resource server + `X-Hive-User-Id` filter; exact-commit starter build; query-parameter context selectors.

Changed files: new files only (two commits: initial Phase 0, then the v2 DDD additions), in this repository (docs, `hive/`, `tests/smoke/`). No change to Hive, B or C.

Database migrations: none.

API / route-operation / manifest changes: draft vocabulary only (`commerce-api` module, 9 resources). Registered only in a throwaway local Hive, since destroyed.

Tests Added: `tests/smoke/phase0-bff-smoke.mjs` (+ `phase0-compose-override.yml`, a local echo upstream and HTTP target allowance).

Tests Executed:
```sh
# in a clean export of hive-platform@a75871a
npm ci && npm run build && ./mvnw -B -q package -DskipTests && npm run build:shell && npm run build:console   # exit 0
node cli/bin/hive.mjs validate hive/drafts/gemstone/commerce-api/resource-manifest.json                        # PASS
node cli/bin/hive.mjs up --build --profile ui --env-file <scratch>/hive.env --http-port 18180 --bff-port 18181 --authorization-port 18182 --project commerce-p0   # exit 0
node cli/bin/hive.mjs doctor --bff http://127.0.0.1:18181 --authorization http://127.0.0.1:18182 --origin http://127.0.0.1:18180   # exit 0
docker compose -p commerce-p0 -f infra/docker-compose/hive.yml -f tests/smoke/phase0-compose-override.yml ... up -d bff echo
node tests/smoke/phase0-bff-smoke.mjs hive/drafts/gemstone/commerce-api/resource-manifest.json                 # 10/10 PASS
node cli/bin/hive.mjs down --volumes --project commerce-p0
```

PASS:
- `hive validate`: draft resource manifest.
- `hive doctor`: BFF readiness, authorization readiness, compatibility of active modules, graph projection (pending 0, dead-lettered 0), route configuration.
- Smoke (10): resource-only module published; PUBLIC operation with a permission rejected (400); PUBLIC GET forwarded with stripped prefix and query; forged `Authorization`, `Cookie`, `X-Hive-User-Id` and custom headers not forwarded; `X-Correlation-Id`/`X-Hive-Route`/`X-Hive-Operation` forwarded; anonymous AUTHENTICATED → 401 `AUTHENTICATION_REQUIRED` without reaching upstream; unsafe call without CSRF → 403; HYBRID anonymous POST reaches upstream without identity or token; HYBRID POST without CSRF → 403; unregistered operation → 404.

FAIL (and how fixed): none.

NOT EXECUTED (and why):
- Signed-in flows: grant → AUTHENTICATED allowed, 403 without grant, and token + `X-Hive-User-Id` forwarding on HYBRID for a session. No IdP was configured in the throwaway stack. The code path is verified by reading `RuntimeProxy.java:126,158`. Phase 1 adds Keycloak to the test harness.
- Doctor checks for OpenFGA, PostgreSQL, Redis, IdP and manifest (flags not supplied; readiness covers DB and graph).
- Any engine code: none exists yet (Phase 1).
- Mermaid diagrams were not rendered by a tool; syntax checked by review only.

Known limitations:
- `hive up --build` retagged the local images `hive/authorization`, `hive/bff`, `hive/default-shell` and `hive/operator-console` at `:0.1.0-dev` to the `a75871a` build. Other local compose projects that use that tag (for example `haramyar`) get these images on their next recreate.
- `hive register` cannot register a resource-only module (it always registers an artifact); client registration will use the admin API for `commerce-api` (ADR-0006).
- Hive doc drift: `docs/versioning.md` says Flyway V1–V7, but V8 exists.

Open Blockers:
- Owner decision on ADR-0004 (and approval of the other proposed ADRs).
- Stakeholder event-storming workshop (gemstone and aidastoor owners) on the hotspots in docs/domain/event-storming.md before Phase 2.
- Persian glossary terms need review by a native-speaking domain expert.

Next phase: Phase 1 — Commerce Foundation (see [roadmap](roadmap.md)).

Commit SHA(s): `d02225a` and the following v2 commit on `phase-0/architecture` (pushed to origin).
