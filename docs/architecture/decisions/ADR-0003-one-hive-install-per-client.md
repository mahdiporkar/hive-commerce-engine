# ADR-0003 — One Hive installation per client (Gaps 6 and 8)

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Context (verified at a75871a)
- `module_key`, `route_key`, `target_key`, `path_prefix`, `role_key` and `group_key` are **globally unique** in one Hive install (migrations V3–V5).
- `HIVE_SESSION` is host-only and `SameSite=Lax`, so the BFF must be same-origin with each storefront domain.
- Each client already gets its own database, bucket, domain and release lifecycle (Section 8 of the master prompt).

## Decision
Each client (gemstone, aidastoor) runs **its own Hive installation** (BFF, authorization, OpenFGA, PostgreSQL, Redis) next to its own engine deployment, behind the client's domain. The engine's Hive vocabulary (module keys `commerce-*`, prefix `/commerce`, roles such as `marketplace-admin`) is then identical across clients, and the manifests in repository A are templates parameterised only by `applicationKey`.

The existing University ERP Hive installation is **not** reused for commerce.

## Alternatives
- **Shared Hive install with per-domain gateways.** Requires prefixing every module, route, prefix, role and group key per client (`gemstone-commerce-catalog`, `/gemstone-commerce`), one OIDC client and redirect per domain, and puts all clients in the blast radius of every Hive upgrade. It stays possible because the templates are parameterised, but it is not the v1 default.
- **Change Hive to scope keys per application.** A platform change that needs an approved Hive ADR. Not requested.

## Consequences
More infrastructure per client (about 5 containers). In return: clean isolation, independent Hive upgrades per client, and no key prefixing.
