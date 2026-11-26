# Built from the root of the monorepo:
#   docker build -f apps/api/Dockerfile -t fermapp-api .
FROM node:22-bookworm-slim AS build
WORKDIR /repo
RUN apt-get update -y && apt-get install -y --no-install-recommends openssl ca-certificates
COPY package.json package-lock.json ./
COPY packages/domain/package.json packages/domain/
COPY apps/api/package.json apps/api/
COPY apps/mobile/package.json apps/mobile/
RUN npm ci --workspace @fermapp/api --workspace @fermapp/domain --include-workspace-root
COPY packages/domain packages/domain
COPY apps/api apps/api
RUN npx --workspace @fermapp/api prisma generate --schema prisma/schema.prisma
RUN npm run build --workspace @fermapp/api
RUN npm prune --omit=dev --workspace @fermapp/api --workspace @fermapp/domain --include-workspace-root

FROM node:22-bookworm-slim
WORKDIR /repo
ENV NODE_ENV=production
RUN apt-get update -y && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /repo/node_modules node_modules
COPY --from=build /repo/package.json package.json
COPY --from=build /repo/packages/domain packages/domain
COPY --from=build /repo/apps/api/package.json apps/api/package.json
COPY --from=build /repo/apps/api/dist apps/api/dist
COPY --from=build /repo/apps/api/prisma apps/api/prisma
USER node
WORKDIR /repo/apps/api
EXPOSE 4000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD node -e "fetch('http://127.0.0.1:4000/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/server.js"]
