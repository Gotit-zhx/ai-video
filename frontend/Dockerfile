FROM node:22.21.1-bookworm-slim AS finance-modern
WORKDIR /source/frontend/modern
COPY modern/package.json modern/package-lock.json ./
RUN npm ci --ignore-scripts
COPY modern ./
COPY i18n /source/frontend/i18n
COPY modern-build.mjs /source/frontend/
RUN npm run build

FROM python:3.12.12-slim@sha256:f3fa41d74a768c2fce8016b98c191ae8c1bacd8f1152870a3f9f87d350920b7c AS assets
WORKDIR /source
COPY . frontend
COPY --from=finance-modern /source/frontend/assets/finance-modern /source/frontend/assets/finance-modern
RUN python3 /source/frontend/package.py /public --layered

FROM nginx:1.29.3-alpine@sha256:b3c656d55d7ad751196f21b7fd2e8d4da9cb430e32f646adcf92441b72f82b14
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=assets /public/stable/ /usr/share/nginx/html/
COPY --from=assets /public/frequent/ /usr/share/nginx/html/
EXPOSE 80 443
