# ADR-0002 — Modular monolith: one Maven module and one schema per bounded context

Status: proposed · Date: 2026-11-01 (revised for master prompt v2 §6.1)

## Decision
One deployable Spring Boot 3.5.x application (same line as Hive, currently 3.5.5), Java 21, built from a Maven multi-module project:

```text
commerce-shared-kernel/   Money, Quantity, typed-id base, LocalizedText, DomainEvent, Period (no Spring)
commerce-platform/        Hive ACL (UserContext, identity binding, tenant check), outbox/inbox infrastructure,
                          ProductionGuard rules, error mapping (Hive PlatformError), config validation
commerce-store/  commerce-catalog/  commerce-listing/  commerce-seller/  commerce-pricing/
commerce-inventory/  commerce-checkout/  commerce-ordering/  commerce-payment/  commerce-fulfillment/
commerce-digital-delivery/  commerce-finance/
commerce-spi/             extension contracts (published Maven artifact)
commerce-app/             Spring Boot application: wiring, Flyway, OpenAPI aggregation
```

Each context module uses the §6.1.4 layout:

```text
domain/          aggregates, VOs, events, domain services, repository ports — plain Java
application/     use cases, transactions, Layer-2 authorization, event handlers
application/published/  the context's in-process API for other contexts: query facades, command
                 interfaces, published DTOs, integration event types (the only package other contexts may import)
infrastructure/  JDBC adapters, outbox writers, provider adapters
api/             REST controllers and DTOs (OpenAPI); never expose domain objects
```

**Persistence:** one PostgreSQL schema per context (`store`, `catalog`, `listing`, `seller`, `pricing`, `inventory`, `checkout`, `ordering`, `payment`, `fulfillment`, `digital`, `finance`, plus `platform` for the outbox, inbox, identity binding and audit). Flyway migrations live in each module (`db/migration/<schema>`). Each context's repositories use a DB role whose `search_path` and grants cover only its schema; this is enforced in integration tests. No cross-schema foreign keys; references are ids.

**Persistence technology:** Spring JDBC (`JdbcClient`) with explicit mappers, not JPA, so domain classes stay annotation-free and aggregate boundaries map to explicit SQL. Optimistic locking uses `revision` in `UPDATE ... WHERE revision = ?`.

**Boundary enforcement (ArchUnit, run in every build):**
- `domain` depends on nothing except `commerce-shared-kernel` and the JDK (no Spring, Jakarta, Jackson, JDBC or Hive).
- A context may import another context only through `..<ctx>.application.published..`.
- `api` must not be imported by `application` or `domain`. Controllers call application services only.
- Hive types and `X-Hive-*` names appear only in `commerce-platform`.

Spring Modulith was considered for its event-publication registry. ArchUnit plus a custom outbox keeps the dependency surface small and gives the same guarantees. Revisit only if the outbox becomes a maintenance burden.

## Consequences
Compile-time module separation plus ArchUnit rules make cross-context leaks fail the build. Extracting a context into a service later means replacing the `published` facade with a client and moving its schema.
