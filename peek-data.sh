#!/usr/bin/env bash
# 项目 A 数据查看器 —— 一眼看穿后端到底存了什么
#
# 用法：
#   bash peek-data.sh              # 概览：各表行数 + 最近会话 + Redis + 健康
#   bash peek-data.sh 78           # 看 78 号会话的完整链路（题目→回答→评分→AI调用）
#   bash peek-data.sh log          # 只看应用日志尾部
#   bash peek-data.sh redis        # 只看 Redis 里有什么
#
# 注意：Windows Git Bash 终端默认 GBK，中文会显示成 ??，
# 脚本内已统一用 --default-character-set=utf8mb4 + SET NAMES utf8mb4 修正。
set -uo pipefail

MYSQL="docker exec ai-coach-mysql mysql -uroot -p123456 -D ai_coach --default-character-set=utf8mb4"
Q() { docker exec ai-coach-mysql mysql -uroot -p123456 -D ai_coach \
        --default-character-set=utf8mb4 -e "SET NAMES utf8mb4; $1" 2>/dev/null; }

TOTAL() { docker exec ai-coach-mysql mysql -uroot -p123456 -D ai_coach -N -e "SELECT COUNT(*) FROM $1" 2>/dev/null; }

line() { printf '%s\n' "--------------------------------------------------------------"; }

echo "====================== 项目 A 数据概览 ======================"
printf "%-18s %s\n" "user"            "$(TOTAL user)"
printf "%-18s %s\n" "interview_session" "$(TOTAL interview_session)"
printf "%-18s %s\n" "question"        "$(TOTAL question)"
printf "%-18s %s\n" "answer"          "$(TOTAL answer)"
printf "%-18s %s\n" "feedback"        "$(TOTAL feedback)"
printf "%-18s %s\n" "ai_call_log"     "$(TOTAL ai_call_log)"
printf "%-18s %s\n" "failed_task"     "$(TOTAL failed_task)"
echo

echo "====================== 最近 8 个会话 ======================"
Q "SELECT s.id, u.username, s.status,
     CASE s.status WHEN 0 THEN '出题中' WHEN 1 THEN '完成' WHEN 2 THEN '失败' ELSE '?' END AS 状态,
     (SELECT COUNT(*) FROM question q WHERE q.session_id=s.id) AS 题数,
     (SELECT COUNT(*) FROM answer a WHERE a.session_id=s.id) AS 已答,
     s.created_at
   FROM interview_session s LEFT JOIN user u ON u.id=s.user_id
   ORDER BY s.id DESC LIMIT 8\G"

MODE="${1:-}"

if [[ "$MODE" == "log" ]]; then
  line; echo "=== 应用日志尾部 ==="; docker logs --tail 40 ai-coach-app 2>&1
  exit 0
fi

if [[ "$MODE" == "redis" ]]; then
  line; echo "=== Redis ==="
  echo "-- key 总数: $(docker exec ai-coach-redis redis-cli dbsize 2>/dev/null)"
  docker exec ai-coach-redis redis-cli --scan --pattern '*' 2>/dev/null | head -25
  exit 0
fi

if [[ "$MODE" =~ ^[0-9]+$ ]]; then
  SID="$MODE"
  line; echo "=== 会话 #$SID 的题目 ==="
  Q "SELECT id, CASE type WHEN 1 THEN '编程' WHEN 2 THEN '场景' WHEN 3 THEN '项目' WHEN 4 THEN '八股' END AS 题型,
       CASE difficulty WHEN 1 THEN '易' WHEN 2 THEN '中' WHEN 3 THEN '难' END AS 难度,
       dimension AS 维度, content FROM question WHERE session_id=$SID ORDER BY id\G"
  line; echo "=== 会话 #$SID 的回答与评分 ==="
  Q "SELECT a.id AS answer_id, a.question_id, a.score AS 得分, a.status AS 状态,
       LEFT(a.content,60) AS 回答,
       LEFT(f.pros,40) AS 优点, LEFT(f.cons,40) AS 不足
     FROM answer a LEFT JOIN feedback f ON f.id=a.feedback_id
     WHERE a.session_id=$SID ORDER BY a.id\G"
  line; echo "=== 会话 #$SID 的 AI 调用记录 ==="
  Q "SELECT id, tool_name, status, duration_ms, created_at FROM ai_call_log
     WHERE user_id=(SELECT user_id FROM interview_session WHERE id=$SID)
     ORDER BY id DESC LIMIT 10\G"
  exit 0
fi

line; echo "=== Redis ==="
echo "key 总数: $(docker exec ai-coach-redis redis-cli dbsize 2>/dev/null)"
docker exec ai-coach-redis redis-cli --scan --pattern '*' 2>/dev/null | head -12

line; echo "=== 服务健康 ==="
curl -s http://localhost:8080/api/health | head -c 200; echo
docker compose ps --format "table {{.Name}}\t{{.Status}}" 2>/dev/null

line; echo "=== 应用日志（最近 15 行）==="
docker logs --tail 15 ai-coach-app 2>&1

echo
echo "提示：bash peek-data.sh <会话ID>  看单个会话完整链路"
