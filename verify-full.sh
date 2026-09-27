#!/bin/bash
# =====================================================
# 项目 A 全链路回归脚本（覆盖 PRD 全部 11 个接口）
# 用法: bash verify-full.sh
# 前提: docker compose up -d 且 ai-coach-app 已 healthy
#       [17][18] 段需要 docker CLI 可用来查 Redis 缓存 key / 失败任务表；
#       无 docker 时该段会判 FAIL（其余段落不受影响）
# =====================================================
cd "$(dirname "$0")" || exit 1

BASE="http://127.0.0.1:8080"
# 注意：必须用函数而不是字符串变量——把命令塞进字符串变量再展开时，里面的 * 会被
# shell 做 glob 展开，把当前目录的文件名当成 URL 塞给 curl（踩过坑）
curl_s() { curl -s --noproxy '*' "$@"; }
PASS=0
FAIL=0

# ---------------------------------------------------------------
# 探测 JSON 解析器
#
# 不能硬编码 `python`：在没把 python 加进 PATH 的机器上（cmd 直接
# `bash verify-full.sh`）会全线报 "python: command not found"，
# 所有断言拿到空值、整份回归假失败。
#
# 而且「PATH 上有这个命令」不等于「它能用」（如 py 启动器没注册任何
# Python），所以每个候选都实际跑一次才算数。
# ---------------------------------------------------------------
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
  "/d/Users/anaconda3/python.exe" \
  "D:/Users/anaconda3/python.exe" \
  "/c/Python313/python.exe" "/c/Python312/python.exe" "/c/Python311/python.exe") || JSON_TOOL=""

if [ -z "$JSON_TOOL" ] && command -v jq >/dev/null 2>&1; then
  JSON_KIND=jq
elif [ -n "$JSON_TOOL" ]; then
  JSON_KIND=py
else
  echo "错误：找不到可用的 python 或 jq，无法解析 JSON。"
  echo "  任选一种即可："
  echo "    1) 装 Python: https://www.python.org/downloads/ （安装时勾选 Add to PATH）"
  echo "    2) 装 jq:     winget install jqlang.jq"
  echo "  或用 Anaconda 自带的 python（若装在 D:\\Users\\anaconda3）："
  echo "    export PATH=\"/d/Users/anaconda3:\$PATH\" && bash verify-full.sh"
  exit 1
fi

# 从 stdin 的 JSON 里按点分路径定位节点（列表用下标，如 data.records.0.questionCount）
# mode: get=输出节点值，len=输出数组长度
_jpath() {
  local out path
  if [ "$JSON_KIND" = "jq" ]; then
    path=$(printf '%s' "$1" | sed -E 's/\.([0-9]+)/[\1]/g')
    if [ "$2" = "len" ]; then
      out=$(jq -r "(.$path // []) | length" 2>/dev/null)
    else
      out=$(jq -r ".$path // empty" 2>/dev/null)
    fi
  else
    out=$("$JSON_TOOL" -c '
import sys, json
path, mode = sys.argv[1], sys.argv[2]
try:
    d = json.load(sys.stdin)
except Exception:
    print("" if mode == "get" else 0); sys.exit()
for k in path.split("."):
    if k == "": continue
    if isinstance(d, list): d = d[int(k)] if int(k) < len(d) else None
    elif isinstance(d, dict): d = d.get(k)
    else: d = None
    if d is None: break
if mode == "len":
    print(len(d) if isinstance(d, list) else 0)
else:
    print("" if d is None else d)
' "$1" "$2" 2>/dev/null)
  fi
  # Windows 下的解释器可能吐 \r\n，不剥掉会让 [ "$a" = "$b" ] 全部判不等
  printf '%s' "$out" | tr -d '\r'
}

jget() { _jpath "$1" get; }   # 取值
jlen() { _jpath "$1" len; }   # 取数组长度

# 预检：应用是否可达。先给出人话提示，而不是让后面 40 项断言集体失败
if ! curl_s -o /dev/null --max-time 5 "$BASE/api/health"; then
  echo "错误：连不上 $BASE/api/health"
  echo "  1) 先启动整套依赖： docker compose up -d"
  echo "  2) 等应用健康：     docker ps   （看 ai-coach-app 是否为 healthy）"
  echo "  3) 若刚改过代码，镜像必须重建： docker compose up -d --build"
  exit 1
fi

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
echo " JSON 解析器: $JSON_KIND ($JSON_TOOL)"
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
  if [ "$STATUS" = "1" ]; then echo "  ... 第 ${i} 次轮询出题完成"; break; fi
  if [ "$STATUS" = "2" ]; then echo "  ... 第 ${i} 次轮询 status=2（失败）"; break; fi
  sleep 2
done
check "出题完成 status" "$STATUS" "1"
QCOUNT=$(echo "$R" | jlen "data.questions")
if [ "$QCOUNT" -ge 3 ]; then ok "生成题目数 = $QCOUNT"; else bad "题目数过少: $QCOUNT"; fi
QID=$(echo "$R" | jget "data.questions.0.id")

# AgentLoop 专属断言：考察维度 + 执行轮数
DIM=$(echo "$R" | jget "data.questions.0.dimension")
if [ -n "$DIM" ]; then ok "首题考察维度 = $DIM"; else bad "首题 dimension 为空（Planner 未生效）"; fi
DIMN=0
for i in $(seq 0 $((QCOUNT - 1))); do
  [ -n "$(echo "$R" | jget "data.questions.$i.dimension")" ] && DIMN=$((DIMN + 1))
done
if [ "$DIMN" = "$QCOUNT" ]; then ok "全部 $QCOUNT 道题都带考察维度"; else bad "仅 $DIMN/$QCOUNT 道题有维度"; fi
ROUNDS=$(echo "$R" | jget data.agentRounds)
if [ "$ROUNDS" = "1" ] || [ "$ROUNDS" = "2" ]; then ok "AgentLoop 执行轮数 = $ROUNDS"; else bad "agentRounds 异常: [$ROUNDS]"; fi

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
check "详情首题考察维度已持久化" "$(echo "$R" | jget data.items.0.dimension)" "$DIM"
check "详情 agentRounds 已持久化" "$(echo "$R" | jget data.agentRounds)" "$ROUNDS"
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

# ---------- 17. 响应缓存 ----------
# 缓存引入了全新的失败模式，必须专门验证：
#   1) 缓存真的生效（否则优化无从谈起）
#   2) 缓存 key 含 userId（否则 A 能读到 B 的缓存 —— 越权，且绕过 403 校验）
#   3) 出题中的会话不被缓存（否则题目生成完了前端还看到空列表）
#   4) 写操作后缓存确实失效（否则数据长期陈旧）
echo ""
echo "[17] 响应缓存生效、失效与越权安全"

# 17.1 详情缓存写入：连读两次，Redis 里应出现 sessionDetail:: 的 key
curl_s -H "$AUTH" "$BASE/api/interview/sessions/$SID" >/dev/null
curl_s -H "$AUTH" "$BASE/api/interview/sessions/$SID" >/dev/null
DC=$(docker exec ai-coach-redis redis-cli --scan --pattern "sessionDetail::*" 2>/dev/null | wc -l | tr -d '\r ')
check "详情缓存已写入 Redis" "$([ "${DC:-0}" -gt 0 ] && echo yes || echo no)" "yes"

# 17.2 列表缓存写入
curl_s -H "$AUTH" "$BASE/api/interview/sessions?page=1&size=10" >/dev/null
LC=$(docker exec ai-coach-redis redis-cli --scan --pattern "sessionList::*" 2>/dev/null | wc -l | tr -d '\r ')
check "列表缓存已写入 Redis" "$([ "${LC:-0}" -gt 0 ] && echo yes || echo no)" "yes"

# 17.3 缓存 key 必须含 userId：缓存已存在时，他人请求仍须 403 而不是命中缓存返回 200
R=$(curl_s -H "$AUTH2" "$BASE/api/interview/sessions/$SID")
check "缓存已存在时他人仍 403" "$(echo "$R" | jget code)" "403"

# 17.4 本人命中缓存仍能拿到正确数据
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions/$SID")
check "本人读详情数据正确" "$(echo "$R" | jget data.questionCount)" "5"

# 17.5 出题中的会话不得被缓存（unless 守卫）
#      新建后立刻读一次详情（此时 status=0、题目尚未落库），
#      等出题完成后再读，必须看到题目。若 status=0 的结果被缓存住，
#      这里会一直读到 0 道题 —— 这正是「缓存最典型的坑」。
JD_NEW="缓存守卫验证-$TS：招聘 Java 后端实习生，熟悉 SpringBoot、MySQL、Redis"
NSID=$(curl_s -X POST "$BASE/api/interview/create" -H "Content-Type: application/json" \
  -H "$AUTH" -d "{\"jdContent\":\"$JD_NEW\"}" | jget data.sessionId)
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions/$NSID")
check "出题中详情 status=0（未被缓存）" "$(echo "$R" | jget data.status)" "0"
for i in $(seq 1 45); do
  ST=$(curl_s -H "$AUTH" "$BASE/api/interview/$NSID" | jget data.status)
  [ "$ST" = "1" ] && break
  [ "$ST" = "2" ] && break
  sleep 2
done
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions/$NSID")
check "出题完成后读到真实题目（非陈旧缓存）" "$(echo "$R" | jget data.questionCount)" "5"

# 17.6 写操作后列表缓存失效：新建的会话必须立刻出现在列表首条
R=$(curl_s -H "$AUTH" "$BASE/api/interview/sessions?page=1&size=10")
check "新建会话已出现在列表（列表缓存已失效）" \
  "$(echo "$R" | jget data.records.0.sessionId)" "$NSID"

# ---------- 18. 熔断降级与失败任务兜底 ----------
# 高可用这条链路有 4 个独立失败模式，逐个验证：
#   1) 熔断状态可观测（否则依赖挂了都不知道）
#   2) 失败任务表可用（死信落库的落点）
#   3) 重试接口的鉴权与状态守卫（越权 / 重复重试会烧 Token）
#   4) 失败态会话能真的被重试恢复（否则兜底链路只是摆设）
echo ""
echo "[18] 熔断降级与失败任务兜底"

# 18.1 健康检查暴露 LLM 熔断器状态
R=$(curl_s "$BASE/api/health")
check "健康检查含熔断器状态=CLOSED" "$(echo "$R" | jget llmCircuitBreaker.state)" "CLOSED"

# 18.2 失败任务表存在且可查（死信落库的落点）
FT=$(docker exec ai-coach-mysql mysql -uroot -p123456 -D ai_coach -N -B \
  -e "SELECT COUNT(*) FROM failed_task;" 2>/dev/null | tr -d '\r ')
check "failed_task 表可查" "$([ -n "$FT" ] && echo yes || echo no)" "yes"

# 18.3 重试不存在的会话 -> 404（不能因为兜底接口就绕过存在性校验）
R=$(curl_s -X POST -H "$AUTH" "$BASE/api/interview/99999999/retry")
check "重试不存在的会话 -> 404" "$(echo "$R" | jget code)" "404"

# 18.4 重试他人会话 -> 403（越权红线，兜底接口同样要守）
R=$(curl_s -X POST -H "$AUTH2" "$BASE/api/interview/$SID/retry")
check "重试他人会话 -> 403" "$(echo "$R" | jget code)" "403"

# 18.5 重试已完成的会话 -> 409（守卫：否则会把已有题目作废并重复烧 AI）
R=$(curl_s -X POST -H "$AUTH" "$BASE/api/interview/$SID/retry")
check "重试已完成的会话 -> 409" "$(echo "$R" | jget code)" "409"

# 18.6 造一个失败态会话，验证重试能真的把它救回来。
#      直接把 status 改成 2（失败）来模拟「出题失败」，比等真实故障可控得多。
docker exec ai-coach-mysql mysql -uroot -p123456 -D ai_coach \
  -e "UPDATE interview_session SET status=2 WHERE id=$SID;" 2>/dev/null
R=$(curl_s -X POST -H "$AUTH" "$BASE/api/interview/$SID/retry")
check "失败态会话可被重试 -> code=0" "$(echo "$R" | jget code)" "0"
check "重试后状态回到出题中(0)" "$(echo "$R" | jget data.status)" "0"

echo ""
echo "=========================================="
echo " 结果: PASS=$PASS  FAIL=$FAIL"
echo "=========================================="
[ "$FAIL" -eq 0 ] || exit 1
