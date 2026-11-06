# Serves the engine's shared admin bundles at versioned addresses. Hive reaches this host server-side and verifies each bundle
# against its registered integrity. To be able to roll back, fetch the previous release too, then, from the repository root:
#   node tools/assemble-admin-bundles.mjs
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY .engine/admin-host/ /usr/share/nginx/html/
