# ADR-0014 — Client configuration

Status: proposed · Date: 2026-11-01

## Decision
Commerce mode, capabilities, currencies, locales and provider selection live in `commerce.*` configuration. At startup it is validated against a versioned JSON Schema (`config/schema/commerce-config-1.0.0.json`), and invalid configuration stops startup with every violation listed. Secrets and endpoints come only from the environment.

Hive feature flags are used only for UI rollout toggles. Industry taxonomy (categories, attributes, product types) is data, loaded through engine APIs or from client-owned seed files, never configuration code.
