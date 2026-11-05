# The engine image for this store: the published engine by version plus this store's extension JARs, nothing else.
# Build from the repository root. Pin ENGINE_IMAGE by digest for production (engine.lock.json).
ARG ENGINE_IMAGE
FROM ${ENGINE_IMAGE}
COPY deploy/extensions/ /app/extensions/
