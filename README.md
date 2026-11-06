# Hive Commerce Engine

Reusable eCommerce and multi-vendor marketplace engine built on the [Hive Platform](https://github.com/mahdiporkar/hive-platform). It supports single-vendor, multi-vendor and hybrid stores; physical and digital products; and configurable categories, attributes, variants and offers.

> Platform owns capabilities. Solution owns business. Consumer owns presentation.

Hive is the platform; this engine is a reusable solution layer; client repositories (for example `gemstone-commerce`, `aidastoor-commerce`) configure, extend, brand and deploy it. Clients consume published engine versions and never fork this code.

**Status: Phases 0–7 complete, with publishing awaiting approval.** Scenarios A–F pass locally. See [phase status](docs/phase-status.md).

## Build

```sh
tools/build-hive-starter.sh --services              # Hive starter and jars from the pinned commit
./mvnw -B clean install                                 # engine + unit, architecture, contract and integration tests (needs Docker)
node --test tests/e2e/commerce-through-bff.test.mjs  # through a real Hive BFF and Keycloak
```

## Documentation

- [Create a store](docs/deployment/new-store.md), [release policy](docs/deployment/release-policy.md), [upgrade and rollback](docs/deployment/upgrade-guide.md), [release notes](docs/deployment/release-notes.md)
- [Architecture overview](docs/architecture/overview.md) and [key flows](docs/architecture/flows.md)
- [Hive integration: verified capability matrix and gaps](docs/architecture/hive-integration.md)
- [Security and two-layer authorization](docs/architecture/security.md)
- [Route operations and resource vocabulary](docs/architecture/route-operations.md)
- [Domain model (DDD)](docs/architecture/domain-model.md): [event storming](docs/domain/event-storming.md), [glossary](docs/domain/glossary.md), [context map](docs/domain/context-map.md), [aggregates](docs/domain/aggregates.md), [domain events](docs/domain/domain-events.md)
- [ERD per bounded context](docs/architecture/erd.md)
- [Extension system (SPI)](docs/architecture/extension-system.md)
- [Repository boundaries](docs/architecture/repository-boundaries.md)
- [Decisions (ADRs)](docs/architecture/decisions) and the [ADR review matrix](docs/architecture/adr-review.md)
- [Roadmap and minimum vertical slice](docs/roadmap.md)

Pinned Hive revision: [`hive/HIVE_REVISION`](hive/HIVE_REVISION).
