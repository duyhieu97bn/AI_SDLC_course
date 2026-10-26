# syntax=docker/dockerfile:1.7
# Ảnh Nginx phục vụ SPA và làm reverse proxy (SADD 3.3). Cấu hình nginx.conf mount từ deploy/nginx.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-fund --no-audit
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 443
