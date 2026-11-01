# Hive Commerce Engine

Reusable eCommerce and multi-vendor marketplace engine built on the [Hive Platform](https://github.com/mahdiporkar/hive-platform). It supports single-vendor, multi-vendor and hybrid stores; physical and digital products; and configurable categories, attributes, variants and offers.

> Platform owns capabilities. Solution owns business. Consumer owns presentation.

Hive is the platform; this engine is a reusable solution layer; client repositories (for example `gemstone-commerce`, `aidastoor-commerce`) configure, extend, brand and deploy it. Clients consume published engine versions and never fork this code.

**Status: Phase 0 (architecture), awaiting approval. No runtime code yet.** See [phase status](docs/phase-status.md).

## Documentation

- [Architecture overview](docs/architecture/overview.md)
- [Hive integration: verified capability matrix and gaps](docs/architecture/hive-integration.md)
- [Security and two-layer authorization](docs/architecture/security.md)
- [Route operations and resource vocabulary](docs/architecture/route-operations.md)
- [Data model](docs/architecture/erd.md)
- [Repository boundaries](docs/architecture/repository-boundaries.md)
- [Decisions (ADRs)](docs/architecture/decisions)
- [Roadmap and minimum vertical slice](docs/roadmap.md)

Pinned Hive revision: [`hive/HIVE_REVISION`](hive/HIVE_REVISION).
