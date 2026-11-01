# ADR-0001 — Record decisions; pin the Hive revision

Status: accepted · Date: 2026-11-01 · Accepted 2026-11-02 after the [architecture review](../adr-review.md)

## Context
Hive is pre-1.0 (`0.1.0-dev.0`) on `platform-v1`, with no published packages and no `main` branch. Its behavior changes between commits.

## Decision
- Every architectural decision is an ADR in `docs/architecture/decisions/`, numbered, never edited after acceptance (supersede instead).
- The engine pins one Hive commit in `hive/HIVE_REVISION` (currently `a75871a64b9f6ab92c762a126e2fc94284a10429`). CI builds the Hive starter from exactly that commit and runs integration tests against Hive images built from it.
- Moving the pin is a reviewed change that re-runs the full integration suite and updates `docs/architecture/hive-integration.md`.

## Consequences
Reproducible builds; Hive upgrades are explicit. Cost: a manual bump per Hive release.
