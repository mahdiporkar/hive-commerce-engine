# Repository boundaries

| Repository | State on 2026-11-01 | Owns | Must not contain |
|---|---|---|---|
| `hive-platform` (`platform-v1`, pinned `a75871a`) | existing platform, read-only for this project | capabilities | any commerce code or vocabulary |
| `hive-commerce-engine` (A) | branch `phase-0/architecture` (Phase 0 docs); no default branch yet | domain, APIs, migrations, ports, digital delivery, admin MFEs, SPI, Hive manifest templates, generic fixtures (incl. handicraft validation fixture), contract tests | client branding, gemstone or prompt rules, AI model names, client credentials |
| `gemstone-commerce` (B) | empty remote (no refs) | storefront, taxonomy seed, config, gemstone extensions, Hive app registration, deployment | copies of catalog, cart, checkout, orders, payments, settlement |
| `aidastoor-commerce` (C) | empty remote (no refs) | storefront, prompt taxonomy, `PromptVerification` extension, Hive app registration, deployment | copies of payments, catalog engine, commission engine |

## Planned layout of repository A

```text
commerce-shared-kernel/      Money, typed ids, LocalizedText, event envelope (plain Java)
commerce-platform/           Hive ACL, outbox/inbox, ProductionGuard rules, config validation
commerce-<context>/          one module per bounded context: domain/ application/ infrastructure/ api/ (ADR-0002)
commerce-spi/                extension interfaces (published Maven artifact)
commerce-app/                Spring Boot application, wiring, OpenAPI
api-client/                  generated OpenAPI client (published Maven artifact)
admin-mfes/                  one package per admin area (React 19 + @hive-platform/react)
hive/templates/              resource / micro-frontend manifests and service descriptor, {{applicationKey}}
hive/drafts/                 rendered drafts for review and offline `hive validate`
hive/HIVE_REVISION           pinned Hive commit
config/schema/               commerce configuration JSON Schemas
fixtures/handicraft/         Scenario D configuration-only fixture
tests/integration/           Testcontainers + real Hive stack
docs/                        architecture, ADRs, API, deployment, testing, phase status
```

Client repositories consume only published artifacts (image digest, Maven, npm), never source.
