# Release policy

An engine release never deploys itself. A store moves to a new version only through a pull request in its own repository
that changes `engine.lock.json`, after that store's tests have passed against the candidate.

## Versions

The engine follows SemVer. One version covers everything released together:

| Artifact | Where it is published | How a store consumes it |
|---|---|---|
| Engine image | GHCR, by digest | `FROM` in `deploy/engine.Dockerfile` |
| Engine jar | GitHub release | Local end-to-end stacks |
| Extension SPI | GitHub Packages (`io.hivecommerce:commerce-spi`) | `provided` dependency of store extensions |
| Admin bundles | GitHub release (`admin-mfes.tar.gz`, SRI-pinned) | Served by the store, registered in its Hive |
| Hive templates and `register.mjs` | GitHub release (`hive-templates.tar.gz`) | `tools/register.mjs` |
| Storefront SDK | GitHub release (`storefront-sdk.tgz`) | `package.json`, bytes pinned by `package-lock.json` |
| Commerce Builder and test kit | GitHub release (`builder.tgz`, `test-kit.tgz`) | `tools/seed.mjs`, `tests/` |
| OpenAPI contract | GitHub release (`openapi.json`) | `tests/contract/` |

`SHA256SUMS` covers every file. `tools/fetch-engine.mjs` refuses a release that does not match.

## What counts as compatible

| Change | Version bump |
|---|---|
| New operation, new optional field, new response field, new capability that defaults to off | Minor |
| New migration that follows the rules below | Minor |
| Bug fix with no contract change | Patch |
| Removed or renamed operation, field or resource key; a request field that becomes required; a stricter rule on existing data; `CommerceExtension.SPI_MAJOR` change | Major |

Before 1.0.0 the same table applies, with a minor bump in place of a major one, and the release notes must call the break
out. Store extensions declare the SPI major they were built against, and the engine refuses to start on a mismatch.

## Database migrations: expand, migrate, contract

The previous engine version must keep running on the new schema, because that is what a rollback does.

1. **Expand.** A release adds tables, nullable columns, columns with defaults, and indexes. The previous version ignores them.
2. **Migrate.** Data moves in the background or on an explicit call, never as a precondition of startup.
3. **Contract.** A column or table is removed only in a later release, once no supported previous version reads it.

Each context owns one Flyway history per schema, and an applied migration is never edited. Flyway tolerates migrations
newer than the running code, which is what lets the previous version start again.

Database rollback scripts are not provided, and restoring a backup is not part of a normal rollback. If a release ever
needs a change that cannot follow these rules, its release notes must say that it cannot be rolled back.

## What a store runs before upgrading (Scenario F)

```bash
ENGINE_VERSION=<candidate> node tools/fetch-engine.mjs
ENGINE_VERSION=<candidate> node --test tests/contract/*.test.mjs
ENGINE_VERSION=<candidate> node tools/build-extensions.mjs
ENGINE_CANDIDATE=<candidate> node --test tests/e2e/upgrade.test.mjs
```

The last command starts the store on its current version, buys through it, upgrades on the same database, buys again, rolls
back, buys again, and checks that every order is intact and the ledger balances. See the [upgrade guide](upgrade-guide.md).
