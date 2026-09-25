FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY apps/admin-web/package.json apps/admin-web/package.json
COPY apps/miniapp/package.json apps/miniapp/package.json
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts
COPY apps/admin-web apps/admin-web
ARG VITE_API_BASE=/api/v1
ARG VITE_OIDC_AUTHORIZATION_ENDPOINT
ARG VITE_OIDC_TOKEN_ENDPOINT
ARG VITE_OIDC_CLIENT_ID
ARG VITE_OIDC_REDIRECT_URI
ENV VITE_API_BASE=${VITE_API_BASE}
ENV VITE_OIDC_AUTHORIZATION_ENDPOINT=${VITE_OIDC_AUTHORIZATION_ENDPOINT}
ENV VITE_OIDC_TOKEN_ENDPOINT=${VITE_OIDC_TOKEN_ENDPOINT}
ENV VITE_OIDC_CLIENT_ID=${VITE_OIDC_CLIENT_ID}
ENV VITE_OIDC_REDIRECT_URI=${VITE_OIDC_REDIRECT_URI}
RUN test -n "$VITE_OIDC_AUTHORIZATION_ENDPOINT" && \
    test -n "$VITE_OIDC_TOKEN_ENDPOINT" && \
    test -n "$VITE_OIDC_CLIENT_ID"
RUN npm run build --workspace=@smartcharge/admin-web

FROM nginx:1.29-alpine
COPY ops/nginx.local.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/admin-web/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
    CMD wget --quiet --spider http://127.0.0.1:8080/healthz || exit 1
