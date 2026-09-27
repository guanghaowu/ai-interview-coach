#!/bin/bash
# =====================================================
# 项目 A 性能压测（产出可写进简历的硬数字）
#
# 用法: bash bench/run-bench.sh
# 前提: docker compose up -d 且 ai-coach-app 已 healthy
#
# 设计原则：
#   - 只压「不烧 AI」或「烧得极少」的路径，避免压测变成烧钱
#   - 业务错误是 HTTP 200 + body.code != 0，必须按业务码统计（bench.js 已处理）
#   - 限流用例只有前 10 个请求会真正触发 AI（令牌桶容量=10/天），成本可控
# =====================================================
cd "$(dirname "$0")/.." || exit 1

# Git Bash(MSYS) 会把「以 / 开头的命令行参数」自动改写成 Windows 路径：
#   --path /api/health  →  --path C:/Program Files/Git/api/health
# 结果服务端收到畸形请求路径，全部返回 400 Bad Request。
# 必须在调用 node 前关掉路径转换（踩过坑，表现为「所有请求都 400」）。
export MSYS_NO_PATHCONV=1

BASE="${BASE:-http://127.0.0.1:8080}"
# 必须用函数而非字符串变量：字符串里的 * 会被 shell glob 展开（踩过坑）
curl_s() { curl -s --noproxy '*' "$@"; }

# ---------- 探测 JSON 解析器（不硬编码 python，见 verify-full.sh 注释） ----------
json_pick() {
  for c in "$@"; do
    [ -n "$c" ] || continue
    if "$c" -c "import json,sys; sys.stdout.write('ok')" >/dev/null 2>&1; then
      printf '%s' "$c"; return 0
    fi
  done
  return 1
}
JSON_TOOL=$(json_pick python python3 py \
  "/c/Users/Administrator/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" \
  "C:/Users/Administrator/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" \
  "/d/Users/anaconda3/python.exe" "D:/Users/anaconda3/python.exe" \
  "/c/Python313/python.exe" "/c/Python312/python.exe") || JSON_TOOL=""
if [ -z "$JSON_TOOL" ]; then
  echo "错误：找不到可用的 python，无法解析 JSON。"
  exit 1
fi

# 从 stdin 取 JSON 的点分路径值
jp() { "$JSON_TOOL" -c '
import sys, json
d = json.load(sys.stdin)
for k in sys.argv[1].split("."):
    d = d[int(k)] if k.isdigit() else d[k]
print(d)
' "$1" 2>/dev/null | tr -d '\r'; }

NODE=$(command -v node || echo "/c/Users/Administrator/.workbuddy-ai/binaries/node/versions/22.22.2-3/node.exe")

# 清空响应缓存（只删 session* 前缀的缓存 key，不动限流 / 会话记忆 / 幂等标记）
# 只测热缓存会得出虚高的数字，必须能分别测冷启动与命中缓存两种情况。
flush_response_cache() {
  local keys
  keys=$(docker exec ai-coach-redis redis-cli --scan --pattern "session*" 2>/dev/null | tr -d '\r' | tr '\n' ' ')
  if [ -n "$keys" ]; then
    # shellcheck disable=SC2086
    docker exec ai-coach-redis redis-cli DEL $keys >/dev/null 2>&1
  fi
}

# 单请求测冷启动延迟（缓存被清空后的第一次，必然穿透到 DB）
cold_latency() {
  local path="$1" label="$2"
  "$NODE" bench/bench.js --url "$BASE" --path "$path" --token "$TOKEN" \
    --concurrency 1 --requests 1 --label "$label" 2>/dev/null \
    | grep -a -E "延迟" | sed 's/.*mean \([0-9.]*\).*/冷启动(穿透到DB) mean=\1ms/'
}

echo "=================================================="
echo " 项目 A 性能压测  BASE=$BASE"
echo " 时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=================================================="

# ---------- 0. 准备压测账号与数据 ----------
TS=$(date +%s)
U="bench$TS"
curl_s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" >/dev/null
TOKEN=$(curl_s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" | jp data.token)
if [ -z "$TOKEN" ]; then echo "登录失败，终止"; exit 1; fi
echo ""
echo "压测账号: $U （token 长度 ${#TOKEN}）"

# 建 1 个会话作为「有数据的列表」样本
JD="招聘 Java 后端实习生，熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ 与高并发设计"
SID=$(curl_s -X POST "$BASE/api/interview/create" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d "{\"jdContent\":\"$JD\"}" | jp data.sessionId)
echo "预热会话 sessionId=$SID，等待 AI 出题完成…"
for i in $(seq 1 45); do
  ST=$(curl_s -H "Authorization: Bearer $TOKEN" "$BASE/api/interview/$SID" | jp data.status)
  [ "$ST" = "1" ] && echo "  出题完成（第 $i 次轮询）" && break
  [ "$ST" = "2" ] && echo "  出题失败，终止" && exit 1
  sleep 2
done

# ---------- 1. 基线：健康检查（无 DB、无业务逻辑） ----------
"$NODE" bench/bench.js --url "$BASE" --path /api/health \
  --concurrency 50 --requests 3000 --warmup 100 --label "基线 /api/health（无 DB，测框架开销）"

# ---------- 2. 读路径：会话列表（分页 + 计数） ----------
flush_response_cache
cold_latency "/api/interview/sessions?page=1&size=10" "列表冷启动"
"$NODE" bench/bench.js --url "$BASE" --path "/api/interview/sessions?page=1&size=10" \
  --token "$TOKEN" --concurrency 50 --requests 2000 --warmup 100 \
  --label "会话列表 /sessions（并发 50，缓存命中）"

# ---------- 3. 读路径：会话详情（多表聚合，最重的读） ----------
flush_response_cache
cold_latency "/api/interview/sessions/$SID" "详情冷启动"
"$NODE" bench/bench.js --url "$BASE" --path "/api/interview/sessions/$SID" \
  --token "$TOKEN" --concurrency 50 --requests 1000 --warmup 50 \
  --label "会话详情 /sessions/{id}（并发 50，缓存命中）"

# ---------- 4. 限流：令牌桶容量 10/天，超出的应被 429 拦截 ----------
echo ""
echo "（下一项会触发至多 10 次真实 AI 调用 —— 令牌桶容量为 10/天，成本可控）"
"$NODE" bench/bench.js --url "$BASE" --path /api/interview/create \
  --method POST --token "$TOKEN" --body "{\"jdContent\":\"$JD\"}" \
  --concurrency 30 --requests 30 \
  --label "出题提交 /create（并发 30，验证令牌桶限流 + 提交延迟）"

# ---------- 5. 汇总：AI 真实耗时 → 同步 vs 异步 ----------
echo ""
echo "=================================================="
echo " AI 真实耗时统计（ai_call_log.duration_ms）"
echo "=================================================="
docker exec ai-coach-mysql mysql -uroot -p123456 -D ai_coach -e "
SELECT COUNT(*) AS 样本数,
       ROUND(AVG(duration_ms)) AS 平均ms,
       MIN(duration_ms) AS 最小ms,
       MAX(duration_ms) AS 最大ms
FROM ai_call_log WHERE duration_ms IS NOT NULL;
" 2>/dev/null

echo ""
echo "=================================================="
echo " 限流实际生效情况（Redis 令牌桶 key）"
echo "=================================================="
docker exec ai-coach-redis redis-cli --scan --pattern 'rate:*' 2>/dev/null | head -5

echo ""
echo "压测结束。"
