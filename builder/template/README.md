# {{displayName}}

Store repository built on [Hive Commerce Engine](https://github.com/{{engineRepository}}), pinned in
[engine.lock.json](engine.lock.json). This repository holds only what belongs to this store: storefront, catalog
schema, configuration, extensions, Hive registration values and deployment. Catalog, cart, checkout, order, payment,
seller, commission and settlement logic stay in the engine.

| Path | What it is |
|---|---|
| `engine.lock.json` | The engine version this store runs. Upgrade through a pull request. |
| `storefront/` | Next.js storefront using `@hive-commerce/storefront-sdk`; `config/` holds store configuration and catalog schema |
| `seed/` | Sample catalog |
| `extensions/` | This store's engine extensions (SPI), if any |
| `hive/` | Hive application for this store, one Hive per store |
| `deploy/` | Images and Compose file; separate database, bucket and domain |
| `tests/` | Contract tests against the engine release, end-to-end acceptance through Hive |

## Run the tests

```bash
node tools/fetch-engine.mjs            # verifies and unpacks the pinned release into .engine/
(cd storefront && npm ci && npm run build)
node --test tests/contract/*.test.mjs
node --test tests/e2e/*.test.mjs       # Docker, Java 21; HIVE_SOURCE_DIR points at built Hive services
```

`ENGINE_RELEASE_DIR=<dir>` makes `fetch-engine` read a locally packaged release instead of the published one.

## Register in Hive

```bash
HIVE_PROVISIONING_PASSWORD=... node tools/register.mjs --authorization <url> --engine-url <url> --modules-url <url>
```

The provisioning credential is a machine administrator of this store's Hive. It never goes to the engine or the
storefront.
