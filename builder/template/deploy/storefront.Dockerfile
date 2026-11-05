# Storefront image. Build from the repository root, after tools/fetch-engine.mjs.
FROM node:22-alpine AS build
WORKDIR /repo
COPY .engine/sdk/ .engine/sdk/
COPY storefront/ storefront/
WORKDIR /repo/storefront
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm ci --no-audit --no-fund && npm run build

FROM node:22-alpine
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
WORKDIR /app
COPY --from=build /repo/storefront/.next/standalone/ ./
COPY --from=build /repo/storefront/.next/static/ ./.next/static/
USER node
EXPOSE 3000
CMD ["node", "server.js"]
