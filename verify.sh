#!/bin/bash
# =====================================================
# Day 2-4 一键验证脚本
# 用法: ./verify.sh
# 前提: Docker Desktop 引擎已启动
# =====================================================
cd "$(dirname "$0")" || exit 1

# 兼容 compose v1 / v2
if docker compose version > /dev/null 2>&1; then
  COMPOSE="docker compose"
else
  COMPOSE="docker-compose"
fi

echo "=========================================="
echo " Day 2-4 验证"
echo "=========================================="

# 1. Docker 检查
echo ""
echo "[1/7] 检查 Docker 引擎..."
if ! docker info > /dev/null 2>&1; then
  echo "FAIL: Docker 引擎未启动，请先双击启动 Docker Desktop"
  exit 1
fi
echo "OK: Docker 已就绪"

# 2. 启动容器
echo ""
echo "[2/7] 启动 MySQL + Redis 容器..."
$COMPOSE up -d mysql redis

# 3. 等待 healthy
echo ""
echo "[3/7] 等待容器 healthy（最多 60s）..."
for i in $(seq 1 30); do
  M=$(docker inspect --format='{{.State.Health.Status}}' ai-coach-mysql 2>/dev/null || echo none)
  R=$(docker inspect --format='{{.State.Health.Status}}' ai-coach-redis 2>/dev/null || echo none)
  if [ "$M" = "healthy" ] && [ "$R" = "healthy" ]; then
    echo "OK: 两个容器都 healthy"
    break
  fi
  echo "  ... mysql=$M redis=$R ($i/30)"
  sleep 2
done

# 4. 建库建表
echo ""
echo "[4/7] 建库建表..."
docker exec -i ai-coach-mysql mysql -uroot -p123456 < src/main/resources/sql/init.sql 2>/dev/null
echo "OK: init.sql 执行完成"

# 5. 验证表
echo ""
echo "[5/7] 验证表结构（应 6 张表）..."
docker exec -i ai-coach-mysql mysql -uroot -p123456 -e "USE ai_coach; SHOW TABLES;" 2>/dev/null

# 6. 启动应用
echo ""
echo "[6/7] 启动 SpringBoot 应用（后台）..."
if [ -z "$DEEPSEEK_API_KEY" ]; then
  echo "WARN: 未检测到 DEEPSEEK_API_KEY 环境变量"
  echo "      Day 2 接口（注册/登录/info）不受影响"
  echo "      Day 3-4 接口（创建会话/评分）会失败"
fi
nohup ./mvnw spring-boot:run > app-run.log 2>&1 &
echo "应用启动中，等待就绪（最多 120s）..."
for i in $(seq 1 60); do
  if curl -s http://localhost:8080/api/health > /dev/null 2>&1; then
    echo "OK: 应用已就绪"
    break
  fi
  sleep 2
done

# 7. 接口测试
echo ""
echo "=========================================="
echo " 接口测试"
echo "=========================================="

echo ""
echo "--- [1] 健康检查 ---"
curl -s http://localhost:8080/api/health; echo ""

echo ""
echo "--- [2] 注册 ---"
curl -s -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test001","password":"123456"}'; echo ""

echo ""
echo "--- [3] 登录（拿 token）---"
LOGIN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test001","password":"123456"}')
echo "$LOGIN"
TOKEN=$(echo "$LOGIN" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "Token 前 30 位: ${TOKEN:0:30}..."

echo ""
echo "--- [4] 不带 token 访问 /info（期望 401）---"
curl -s -w " [HTTP %{http_code}]" http://localhost:8080/api/user/info; echo ""

echo ""
echo "--- [5] 带 token 访问 /info（期望 code=0）---"
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/user/info; echo ""

echo ""
echo "--- [6] 创建面试会话 / AI 出题（需 DEEPSEEK_API_KEY）---"
curl -s -X POST http://localhost:8080/api/interview/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jdContent":"招聘 Java 后端实习生，要求熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ 和分布式锁，有高并发项目经验优先"}'; echo ""

echo ""
echo "=========================================="
echo " 验证结束（应用日志见 app-run.log）"
echo "=========================================="
