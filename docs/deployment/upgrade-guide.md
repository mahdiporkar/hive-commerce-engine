# Upgrading and rolling back a store

Each store upgrades on its own schedule. Nothing here touches another store.

## Upgrade

1. Read the [release notes](release-notes.md) for every version you cross.
2. Run the candidate checks from the [release policy](release-policy.md#what-a-store-runs-before-upgrading-scenario-f).
3. In a pull request, change `engine.lock.json`, then refresh the pinned SDK:

   ```bash
   node tools/fetch-engine.mjs
   (cd storefront && npm install ../.engine/sdk/storefront-sdk.tgz)
   ```

4. Take a database backup. The migrations are additive, but the backup is your protection against anything unforeseen.
5. Deploy the engine image. It runs its migrations at startup with the owner role, then serves with the runtime role.
6. Register the new version in the store's Hive. New operations and resources are added and existing ones are kept. The new
   admin bundles are activated:

   ```bash
   HIVE_PROVISIONING_PASSWORD=... node tools/register.mjs --authorization <url> --engine-url <url> --modules-url <url>
   ```

7. Deploy the admin bundle host and the storefront. Fetch the current release as well as the new one before running
   `node tools/assemble-admin-bundles.mjs`, so the host keeps the previous bundles at their versioned addresses for a rollback.

The order matters for one reason: the engine must have an operation before Hive routes to it, and Hive must route to it
before a storefront or admin bundle calls it.

## Roll back

1. Redeploy the previous engine image on the same database. Do not restore the database, and do not undo migrations.
2. Activate the previous admin bundles: `POST /admin/modules/<module>/artifacts/<previous version>/activate` for each module.
   A module that the previous version does not have is switched off with `POST /admin/modules/<module>/deactivate`.
3. Redeploy the previous storefront.

Operations added by the newer version can stay registered in Hive. Without a handler in the previous engine they answer
404, and nothing calls them once the previous storefront and bundles are back.

Data written while on the newer version stays. Orders, payments and ledger entries remain valid for the previous version.
Data that only the newer version understands (for example reviews, or product media added in 0.2.0) is kept in its tables
and returns when you upgrade again.

## After a rollback

A store that rolled back can upgrade again at any time: Flyway sees the migrations as already applied.
