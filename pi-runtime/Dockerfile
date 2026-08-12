FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --omit=dev
COPY src src
USER node
EXPOSE 8090
CMD ["npm", "start"]
