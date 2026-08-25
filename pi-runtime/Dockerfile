FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --no-audit --no-fund --registry=https://registry.npmmirror.com
COPY src src
USER node
EXPOSE 8090
CMD ["npm", "start"]
