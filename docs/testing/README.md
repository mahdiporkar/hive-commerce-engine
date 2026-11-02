# Testing

| Suite | Command | Needs | What it proves |
|---|---|---|---|
| Unit (domain) | `./mvnw -B test -pl commerce-shared-kernel,commerce-store,commerce-catalog -am` | Java 21 | Aggregate invariants: money, localized text, store mode rules, attribute types, product-type versioning, product and variant validation |
| Architecture | part of `./mvnw -B install` (`ArchitectureTest`) | Java 21 | ADR-0002 boundaries: plain-Java domain, contexts only through `application.published`, Hive types only in the platform, controllers → application only, every handler declares its Hive operation, each module's SQL names only its own schema |
| Route contract | part of `./mvnw -B install` (`RouteContractTest`) | Java 21 | `hive/templates/commerce-service.json` and the engine handlers describe the same operations; every `/v1/admin/**` operation is AUTHENTICATED with a declared resource action |
| Engine integration | part of `./mvnw -B install` (`EngineIntegrationTest`) | Docker | Real PostgreSQL with forced RLS and a runtime role that owns nothing; real JWT validation via JWKS; allowed operator flow; anonymous, forged, invalid-token, wrong-tenant, binding-mismatch and wrong-operation callers denied; cross-store reads return 404; no DELETE; published schemas immutable |
| Through Hive (e2e) | `node --test tests/e2e/commerce-through-bff.test.mjs` | Docker, Java 21, Node 22+, built jars (below) | Real Hive BFF + authorization + OpenFGA + Keycloak: granted operator creates a product through `/api/routes/commerce/...`; ungranted and anonymous callers are denied by Hive **before the engine is contacted** (checked in the engine's access log); CSRF enforced; forged identity rejected; revocation effective |

## Preparing the end-to-end suite

```sh
tools/build-hive-starter.sh --services   # Hive starter + BFF/authorization jars from hive/HIVE_REVISION (into .local/)
./mvnw -B install                        # engine jar (commerce-app/target/commerce-engine.jar) and all Maven suites
node --test tests/e2e/commerce-through-bff.test.mjs
```

The suite binds ports 29500–29521 on 127.0.0.1, creates throwaway containers (Keycloak 26.3.3, PostgreSQL 17.6, OpenFGA 1.18.1, Redis 8.2.1) with random credentials, writes service logs to `.local/e2e/`, and removes the containers and volumes when it finishes.

## Notes

- Testcontainers is pinned to 1.21.4 because the version managed by Spring Boot 3.5.5 (1.21.3) is rejected by Docker Engine 29.
- The e2e suite waits for Hive grants by probing the protected operation itself, not `/api/me/context`. Hive computes permission hints only for applications with active micro-frontend modules (see the Phase 1 report).
- Scenario D (configuration-only handicraft store) runs in `CatalogStorefrontIntegrationTest` from `fixtures/handicraft/catalog.json`.
- `OpenApiContractTest` compares the live API with `docs/api/openapi.json`; regenerate deliberately with `-Dopenapi.write=true`.
