FROM node:20-alpine

# Install build dependencies for sqlite3 compilation if needed
RUN apk add --no-cache python3 make g++

WORKDIR /app

COPY package*.json ./

RUN npm ci

COPY tsconfig.json ./
COPY src ./src
COPY public ./public

# Compile TypeScript
RUN npm run build

# Clean devDependencies to keep container light
RUN npm prune --production

# Create persistent storage directories
RUN mkdir -p /app/data /app/uploads

ENV PORT=3000
ENV UPLOAD_DIR=/app/uploads
ENV DB_PATH=/app/data/qsyncphoto.db

EXPOSE 3000

CMD ["node", "dist/index.js"]
