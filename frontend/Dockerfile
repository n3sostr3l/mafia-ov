
# syntax=docker/dockerfile:1.7

FROM node:24.14.0-alpine AS build

WORKDIR /build

ENV NODE_OPTIONS="--max-old-space-size=384"

COPY package.json package-lock.json ./

# Кэш npm между сборками
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline --no-audit --no-fund

COPY . .

RUN npm run build

FROM nginx:1.28.0-alpine

COPY --from=build /build/dist/browser /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
