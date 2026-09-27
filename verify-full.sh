#!/bin/bash
# =====================================================
# 项目 A 全链路回归脚本（覆盖 PRD 全部 11 个接口）
# 用法: bash verify-full.sh
# 前提: docker compose up -d 且 ai-coach-app 已 healthy
# =====================================================
cd "$(dirname "$0")" || exit 1

BASE="http://127.0.0.1:8080"
# 注意：必须用函数而不是字符串变量——把命令塞进字符串变量再展开时，里面的 * 会被
# shell 做 glob 展开，把当前目录的文件名当成 URL 塞给 curl（踩过坑）
curl_s() { curl -s --noproxy '*' "$@"; }
PASS=0
FAIL=0

# 从 stdin 的 JSON 里按点分路径取值（列表用下标）
jget() {
  python -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(''); sys.exit()
for k in sys.argv[1].split('.'):
    if k == '': continue
    if isinstance(d, list): d = d[int(k)] if int(k) < len(d) else None
    elif isinstance(d, dict): d = d.get(k)
    else: d = None
    if d is None: break
print('' if d is None else d)
" "$1"
}

ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
check() { # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1 = $2"; else bad "$1: 期望 [$3] 实际 [$2]"; fi
}

TS=$(date +%s)
USER="reg$TS"
PASSWD="123456"

echo "=========================================="
echo " 项目 A 全链路回归  $(date '+%F %T')"
echo "=========================================="

# ---------- 1. 健康检查 ----------
echo ""
echo "[1] GET /api/health"
R=$(curl_s "$BASE/api/health")
check "health.status" "$(echo "$R" | jget status)" "UP"

# ---------- 2. 注册 ----------
echo ""
echo "[2] POST /api/user/register"
R=$(curl_s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASSWD\"}")
check "register.code" "$(echo "$R" | jget code)" "0"

# ---------- 3. 登录 ----------
echo ""
echo "[3] POST /api/user/login"
R=$(curl_s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASSWD\"}")
TOKEN=$(echo "$R" | jget data.token)
check "login.code" "$(echo "$R" | jget code)" "0"
if [ -n "$TOKEN" ]; then ok "拿到 token (${#TOKEN} 字符)"; else bad "token 为空"; fi
AUTH="Authorization: Bearer $TOKEN"

# ---------- 4. 鉴权红线 ----------
echo ""
echo "[4] GET /api/user/info 不带 token（期望 401）"
CODE=$(curl_s -o /dev/null -w '%{http_code}' "$BASE/api/user/info")
check "no-token HTTP" "$CODE" "401"

# ---------- 5. 查用户信息 ----------
echo ""
echo "[5] GET /api/user/info"
R=$(curl_s -H "$AUTH" "$BASE/api/user/info")
check "info.code" "$(echo "$R" | jget code)" "0"
check "info.username" "$(echo "$R" | jget data.username)" "$USER"

# ---------- 6. 改用户信息 ----------
echo ""
echo "[6] PUT /api/user/info（新接口）"
R=$(curl_s -X PUT "$BASE/api/user/info" -H "Content-Type: application/json" -H "$AUTH" \
  -d '{"nickname":"回归测试昵称","avatar":"https://example.com/a.png"}')
check "update.code" "$(echo "$R" | jget code)" "0"
R=$(curl_s -H "$AUTH" "$BASE/api/user/info")
check "update 后 nickname 已生效" "$(echo "$R" | jget data.nickname)" "回归测试昵称"

# ---------- 7. 创建会话 ----------
echo ""
echo "[7] POST /api/interview/create（异步出题）"
R=$(curl_s -X POST "$BASE/api/interview/create" -H "Content-Type: application/json" -H "$AUTH" \
  -d '{"jdContent":"招聘 Java 后端实习生，要求熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ，有高并发项目经验优先"}')
SID=$(echo "$R" | jget data.sessionId)
check "create.code" "$(echo "$R" | jget code)" "0"
check "create 初始 status（0=AI出题中）" "$(echo "$R" | jget data.status)" "0"
if [ -n "$SID" ]; then ok "sessionId=$SID"; else bad "sessionId 为空，终止"; exit 1; fi

# ---------- 8. 出题守卫（未完成时提交回答应被拒） ----------
echo ""
echo "[8] 出题未完成时提交回答（期望被拦截，非 0 业务码）"
R=$(curl_s -X POST "$BASE/api/interview/answer" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"questionId\":1,\"content\":\"抢跑测试\"}")
GCODE=$(echo "$R" | jget code)
if [ "$GCODE" != "0" ] && [ -n "$GCODE" ]; then
  ok "守卫生效 code=$GCODE msg=$(echo "$R" | jget message)"
else
  bad "守卫未生效: $R"
fi

# ---------- 9. 轮询出题结果 ----------
echo ""
echo "[9] GET /api/interview/{sessionId} 轮询出题（最多 90s）"
STATUS=0
for i in $(seq 1 45); do
  R=$(curl_s -H "$AUTH" "$BASE/api/interview/$SID")
  STATUS=$(echo "$R" | jget data.status)
  QN=$(echo "$R" | jget "data.questions")
  if [ "$STATUS" = "1" ]; then echo "  ... 第 ${i} 次轮询出题完成"; break; fi
  if [ "$STATUS" = "2" ]; then echo "  ... 第 ${i} 次轮询 status=2（失败）"; break; fi
  sleep 2
done
check "出题完成 status" "$STATUS" "1"
QCOUNT=$(echo "$R" | python -c "import sys,json;d=json.load(sys.stdin);print(len(d.get('data',{}).get('questions') or []))")
if [ "$QCOUNT" -ge 3 ]; then ok "生成题目数 = $QCOUNT"; else bad "题目数过少: $QCOUNT"; fi
QID=$(echo "$R" | jget "data.questions.0.id")

# ---------- 10. 会话列表 ----------
echo ""
echo "[10] GET /api/interview/sessions（新接口，分页）"
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions?page=1&size=10")
check "sessions.code" "$(echo "$R" | jget code)" "0"
check "sessions.page" "$(echo "$R" | jget data.page)" "1"
check "sessions.size" "$(echo "$R" | jget data.size)" "10"
TOTAL=$(echo "$R" | jget data.total)
if [ "$TOTAL" -ge 1 ]; then ok "sessions.total = $TOTAL"; else bad "total 应为 >=1"; fi
check "列表首条 questionCount" "$(echo "$R" | jget data.records.0.questionCount)" "$QCOUNT"
check "列表首条 answeredCount" "$(echo "$R" | jget data.records.0.answeredCount)" "0"

# ---------- 11. 会话详情 ----------
echo ""
echo "[11] GET /api/interview/sessions/{sessionId}（新接口）"
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions/$SID")
check "detail.code" "$(echo "$R" | jget code)" "0"
check "detail.questionCount" "$(echo "$R" | jget data.questionCount)" "$QCOUNT"
check "detail.answeredCount" "$(echo "$R" | jget data.answeredCount)" "0"
check "detail 首题尚未作答" "$(echo "$R" | jget data.items.0.answerContent)" ""

# ---------- 12. 提交回答 ----------
echo ""
echo "[12] POST /api/interview/answer"
R=$(curl_s -X POST "$BASE/api/interview/answer" -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"questionId\":$QID,\"content\":\"我会用 Redis 做缓存层，热点数据走本地 Caffeine，分布式场景用 Redis 集群 + 令牌桶限流；数据库侧通过索引优化和分页避免深分页。\"}")
AID=$(echo "$R" | jget data.answerId)
check "answer.code" "$(echo "$R" | jget code)" "0"
check "answer 初始 status（0=待评分）" "$(echo "$R" | jget data.status)" "0"
if [ -n "$AID" ]; then ok "answerId=$AID"; else bad "answerId 为空，终止"; exit 1; fi

# ---------- 13. 轮询评分 ----------
echo ""
echo "[13] GET /api/interview/answer/{answerId} 轮询评分（最多 90s）"
STATUS=0
for i in $(seq 1 45); do
  R=$(curl_s -H "$AUTH" "$BASE/api/interview/answer/$AID")
  STATUS=$(echo "$R" | jget data.status)
  if [ "$STATUS" = "1" ]; then echo "  ... 第 ${i} 次轮询评分完成"; break; fi
  if [ "$STATUS" = "2" ]; then echo "  ... 第 ${i} 次轮询 status=2（失败）"; break; fi
  sleep 2
done
check "评分完成 status" "$STATUS" "1"
SCORE=$(echo "$R" | jget data.score)
if [ -n "$SCORE" ]; then ok "score = $SCORE"; else bad "score 为空"; fi
PROS=$(echo "$R" | jget data.pros)
if [ -n "$PROS" ]; then ok "pros 已返回"; else bad "pros 为空"; fi

# ---------- 14. 详情回读（回答 + 反馈） ----------
echo ""
echo "[14] 回读会话详情，确认回答与评分已落库"
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions/$SID")
check "detail.answeredCount 更新为 1" "$(echo "$R" | jget data.answeredCount)" "1"
check "首题 answerStatus" "$(echo "$R" | jget data.items.0.answerStatus)" "1"
check "首题 score 已回填" "$(echo "$R" | jget data.items.0.score)" "$SCORE"
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions?page=1&size=10")
check "列表 answeredCount 更新为 1" "$(echo "$R" | jget data.records.0.answeredCount)" "1"

# ---------- 15. 越权红线 ----------
echo ""
echo "[15] 越权与不存在资源的错误码"
OTHER=$((SID+99999))
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions/$OTHER")
check "不存在的会话 -> 404" "$(echo "$R" | jget code)" "404"
R=$(curl_s -H "$AUTH" "$BASE/api/interview/answer/$OTHER")
check "不存在的回答 -> 404" "$(echo "$R" | jget code)" "404"

# 注册第二个用户，尝试读第一个用户的会话 / 回答（横向越权）
U2="reg2$TS"
R=$(curl_s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$U2\",\"password\":\"$PASSWD\"}")
R=$(curl_s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$U2\",\"password\":\"$PASSWD\"}")
TOKEN2=$(echo "$R" | jget data.token)
AUTH2="Authorization: Bearer $TOKEN2"
R=$(curl_s -H "$AUTH2" "$BASE/api/interview/sessions/$SID")
check "他人会话详情 -> 403" "$(echo "$R" | jget code)" "403"
R=$(curl_s -H "$AUTH2" "$BASE/api/interview/answer/$AID")
check "他人回答详情 -> 403" "$(echo "$R" | jget code)" "403"
R=$(curl_s -H "$AUTH2" "$BASE/api/interview/sessions?page=1&size=10")
check "他人会话列表为空" "$(echo "$R" | jget data.total)" "0"

# 重复注册 + 错误密码
R=$(curl_s -X POST "$BASE/api/user/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASSWD\"}")
check "重复注册 -> 409" "$(echo "$R" | jget code)" "409"
R=$(curl_s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"wrong-pwd\"}")
check "密码错误 -> 401" "$(echo "$R" | jget code)" "401"

# ---------- 16. 接口文档 ----------
echo ""
echo "[16] Knife4j 接口文档"
C1=$(curl_s -o /dev/null -w '%{http_code}' "$BASE/doc.html")
check "GET /doc.html" "$C1" "200"
C2=$(curl_s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")
check "GET /v3/api-docs" "$C2" "200"

echo ""
echo "=========================================="
echo " 结果: PASS=$PASS  FAIL=$FAIL"
echo "=========================================="
[ "$FAIL" -eq 0 ] || exit 1
