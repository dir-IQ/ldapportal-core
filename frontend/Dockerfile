# Frontend image — Vue/Vite bundle on nginx. Self-contained multi-stage
# npm build; the runtime config is an envsubst template so ONE image runs
# on every platform (docker-compose, Kubernetes/EKS, Fly.io). The backend
# host the SPA proxies /api/v1 to is supplied at runtime via BACKEND_APP —
# see nginx.conf.template for the per-platform values.
#
# Build context is frontend/:
#   docker build -f frontend/Dockerfile -t ldapportal-frontend:dev frontend/
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Public base path the SPA is served under. Leave at "/" for a root
# deployment; pass --build-arg VITE_BASE_PATH=/idm/ when a reverse proxy
# (e.g. a WebSEAL junction) exposes the app under a URL prefix. Baked into
# the bundle, so build one image per prefix. See docs/deployment-webseal-dual-frontend.md §3.5.
ARG VITE_BASE_PATH=/
RUN VITE_BASE_PATH="$VITE_BASE_PATH" npm run build

FROM nginx:alpine
# Default BACKEND_APP suits docker-compose (the backend service is "app");
# override it on Kubernetes/Fly. NGINX_ENVSUBST_FILTER restricts the stock
# entrypoint's envsubst to just BACKEND_APP so nginx runtime vars survive.
RUN apk add --no-cache --upgrade 'expat>=2.8.2-r0'
ENV BACKEND_APP=app
ENV NGINX_ENVSUBST_FILTER=BACKEND_APP
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
