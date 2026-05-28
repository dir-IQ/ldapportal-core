# DOCKER-COMPOSE ONLY. For Fly.io deployment, build frontend/Dockerfile.fly
# (which uses an envsubst template + .flycast-aware nginx config). The
# `proxy_pass http://app:8080;` in nginx.conf only resolves on the
# docker-compose network where the backend service is named "app".
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
