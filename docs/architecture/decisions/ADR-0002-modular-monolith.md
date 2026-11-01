# ADR-0002 — Modular monolith with enforced boundaries

Status: proposed · Date: 2026-11-01

## Decision
One Spring Boot 3.5.x (same line as Hive, currently 3.5.5) / Java 21 / Maven application, packaged per bounded context: `catalog`, `store`, `seller`, `pricing`, `cart`, `checkout`, `order`, `payment`, `inventory`, `fulfillment`, `finance`, `digital`, `config`, `platform`. Each module exposes an `api` package (application services, DTOs, events); `internal` packages are private.

Boundaries are enforced with **ArchUnit** tests (plain JUnit, no runtime coupling, no extra Spring version constraint). Spring Modulith remains an option if its event-publication registry proves useful; adopting it needs a compatibility check against the pinned Boot version, done in Phase 1 by a build, not by assumption.

Hexagonal ports only at integration edges: `PaymentProvider`, `FileStorageProvider`, `NotificationProvider`, `TaxCalculator`, `ShippingRateProvider`, `MalwareScanner`, `OutboxPublisher`. No ports for in-process module calls.

## Alternatives
Microservices: rejected for v1 (operational cost, distributed transactions across checkout, reservation and ledger).

## Consequences
Checkout, reservation and ledger posting share one database transaction. Extracting a module later stays possible because cross-module access goes through `api` packages and events.
