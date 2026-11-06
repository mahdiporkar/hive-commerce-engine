# ADR-0019 — One origin per store through a gateway

Status: accepted · Date: 2026-11-06

## Context
`HIVE_SESSION` is a same-origin cookie (Section 5, gap 6), so a storefront and its BFF must share an origin. The first attempt
used Next.js rewrites. They do not forward the browser's host, so the BFF built its OIDC redirect for its own address and the
browser left the store after signing in.

## Decision
1. Every store has a gateway in front of the storefront and the BFF: `/api`, `/auth`, `/oauth2` and `/login` go to the BFF with
   the browser's `Host`, `X-Forwarded-Proto` and `X-Forwarded-Port`. Everything else goes to the storefront.
2. `/hooks` goes straight to the engine, so payment webhooks never pass through the BFF.
3. End-to-end tests run the same topology using a small gateway in the test kit, so a test cannot pass on a path production does
   not use. Storefronts carry no rewrites.
4. `X-Forwarded-Port` is required: Tomcat otherwise assumes the default port for the forwarded scheme, and the redirect loses
   any non-standard port.

## Alternatives
- *Next.js rewrites or middleware.* Rejected for the reason above, and a storefront outage would also take down sign-in.
- *A shared gateway for all stores.* Rejected: ADR-0003 gives each store its own Hive, and a shared gateway would couple their
  availability and configuration.

## Consequences
- One more small container per store. It uses the unprivileged nginx image and holds no secrets.
- The production template has been validated with `docker compose config` and its image builds, but it has not served real
  traffic; the tests use the kit's gateway.
- Hard to reverse: low.
