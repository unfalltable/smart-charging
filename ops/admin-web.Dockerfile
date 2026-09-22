FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY apps/admin-web/package.json apps/admin-web/package.json
COPY apps/miniapp/package.json apps/miniapp/package.json
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts
COPY apps/admin-web apps/admin-web
ARG VITE_API_BASE=/api/v1
ARG VITE_LOCAL_MODE=true
ENV VITE_API_BASE=${VITE_API_BASE}
ENV VITE_LOCAL_MODE=${VITE_LOCAL_MODE}
RUN npm run build --workspace=@smartcharge/admin-web

FROM nginx:1.29-alpine
COPY ops/nginx.local.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/admin-web/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
    CMD wget --quiet --spider http://127.0.0.1:8080/healthz || exit 1
