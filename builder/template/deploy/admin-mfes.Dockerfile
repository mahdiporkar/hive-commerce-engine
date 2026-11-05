# Serves the engine's shared admin bundles from the pinned release. Hive reaches this host server-side and verifies each
# bundle against its registered integrity. Build from the repository root, after tools/fetch-engine.mjs.
ARG ENGINE_VERSION
FROM nginxinc/nginx-unprivileged:1.29-alpine
ARG ENGINE_VERSION
COPY .engine/${ENGINE_VERSION}/admin-mfes/modules/ /usr/share/nginx/html/
