# ---------- 阶段 1：构建静态资源 ----------
FROM node:20-alpine AS builder

WORKDIR /build

# 先只拷依赖清单，利用 Docker 层缓存：源码改动不会触发重新装依赖
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- 阶段 2：nginx 托管 ----------
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
