#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AgentLoop 离线评测脚本
======================
目的：量化「维度一致性」与「覆盖度」两个指标，并给出
「改造前（单次出题） vs 改造后（AgentLoop 三角色闭环）」的对照。

prompt 全部从 InterviewAiService.java 原文复刻，保证评的是真实系统逻辑。

指标口径
--------
1. 维度一致性 = 裁判判定「题目内容确实属于其标注维度」的题数 / 总题数
2. 覆盖度     = 题目覆盖到的 JD 核心技术点数 / JD 核心技术点总数

为什么用「裁判模型」而不是人工：
    人工标注 8×2×5=80 道题不现实；用 temperature=0 的独立裁判调用、
    且裁判看不到该组属于 baseline 还是 agentloop（只给题目不看来源），
    可最大限度降低偏置。这是 LLM 应用离线评测的通行做法（LLM-as-judge）。

用法：
    export DEEPSEEK_API_KEY=sk-xxx
    python eval-agentloop.py            # 全量 8 条 JD
    python eval-agentloop.py --jd 3     # 只跑 3 条，快速验证

安全：Key 只从环境变量读，脚本内不落盘、不打印。
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

API_KEY = os.environ.get("DEEPSEEK_API_KEY")
if not API_KEY:
    sys.exit("请先 export DEEPSEEK_API_KEY=sk-xxx")

BASE_URL = "https://api.deepseek.com/v1"
MODEL = "deepseek-chat"

# ---------------- 评测集：8 条 JD，覆盖不同技术栈组合 ----------------
JD_CORPUS = [
    "招聘 Java 后端实习生，熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ 与高并发设计",
    "招聘 Java 后端实习生，要求掌握 JVM 调优、多线程并发编程、MySQL 索引与事务，有分布式系统经验优先",
    "招聘 Java 后端实习生，熟悉 Spring Cloud 微服务、Nacos 注册中心、Gateway 网关、Redis 分布式锁",
    "招聘 Java 后端实习生，要求熟悉 Kafka 消息队列、Elasticsearch 搜索引擎、MongoDB，了解分库分表",
    "招聘 Java 后端实习生，熟悉 MyBatis、MySQL 慢查询优化、Redis 缓存穿透击穿雪崩、线程池",
    "招聘 Java 后端实习生，掌握 Spring、SpringMVC、MyBatis、MySQL、Linux 常用命令、Git",
    "招聘 Java 后端实习生，要求了解 Docker 容器化、Kubernetes、CI/CD、Prometheus 监控、Nginx",
    "招聘 Java 后端实习生，熟悉分布式事务 Seata、RocketMQ 可靠消息、Redis 分布式锁、接口幂等设计",
]


# ---------------- DeepSeek 调用 ----------------
def chat(system: str, user: str, temperature: float = 0.7, max_tokens: int = 2000,
         retries: int = 3) -> str:
    payload = {
        "model": MODEL,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user}],
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    req = urllib.request.Request(
        BASE_URL + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + API_KEY},
        method="POST",
    )
    last_err = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                body = json.loads(resp.read().decode("utf-8"))
                return body["choices"][0]["message"]["content"]
        except Exception as e:                      # noqa: BLE001
            last_err = e
            time.sleep(2 * (i + 1))
    raise RuntimeError("DeepSeek 调用失败: %s" % last_err)


def parse_json(text: str):
    """模型常把 JSON 包在 ```json ``` 里，这里做一层剥离。"""
    s = text.strip()
    if s.startswith("```"):
        s = s.split("```")[1] if s.count("```") >= 2 else s[3:]
        if s.startswith("json"):
            s = s[4:]
    s = s.strip()
    try:
        return json.loads(s)
    except Exception:                              # noqa: BLE001
        return None


# ================= 模式 A：baseline（改造前：单次调用，无维度概念）=================
# 关键：改造前的题目**没有 dimension 字段**（数据库里该列就是后加的），
# 所以 baseline 侧「维度一致性」无从谈起，只能比覆盖度。
# 若给 baseline 也加 dimension 约束，等于把改造后的优势送给对照组，评测就失效了。
BASELINE_SYS = """你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
你的任务是阅读一份 JD（职位描述），生成 5 道高质量面试题。

要求：
1. 题目必须紧扣 JD 中出现的核心技术栈，不要偏离
2. 题型混合，type 取值：1=编程题 2=场景题 3=项目题 4=八股题
3. 难度分布：至少一道中等(difficulty=2)，可含一道难题(difficulty=3)
4. 每道题要具体、可作答，避免"请介绍下 Java"这类空泛问题
5. 只返回 JSON，不要任何解释文字

返回格式：{"questions":[{"type":1,"content":"...","difficulty":2}]}
"""

# ================= 模式 B：AgentLoop（复刻 InterviewAiService.java）=================
PLANNER_SYS = """你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
你的任务是阅读一份 JD（职位描述），把它拆解成 3-5 个考察维度。

要求：
1. 维度必须来自 JD 中真实出现的核心技术点，不要凭空添加 JD 没提的技术
2. 维度之间不要重叠，例如「Redis 缓存」与「Redis 持久化」应合并成一个维度
3. 维度名要短（不超过 12 个字），如「并发与线程安全」「MySQL 索引优化」
4. focus 用一句话说明这个维度具体考察什么，供后续出题参考
5. 所有维度的 count 之和为 5
6. 只返回 JSON，不要任何解释文字

返回格式：{"dimensions":[{"name":"...","count":2,"focus":"..."}]}
"""

EXECUTOR_SYS = """你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
你的任务是根据 JD 与给定的考察计划，生成 5 道高质量面试题。

要求：
1. 题目必须紧扣 JD 中出现的核心技术栈，不要偏离
2. 每道题的 dimension 字段必须填写它所属的考察维度名（取自考察计划）
3. 题型混合，type 取值：1=编程题 2=场景题 3=项目题 4=八股题
4. 难度分布：至少一道中等(difficulty=2)，可含一道难题(difficulty=3)
5. 每道题要具体、可作答，避免"请介绍下 Java"这类空泛问题
6. 只返回 JSON，不要任何解释文字

【最重要的一条】维度一致性：
每道题的 content 必须真正属于它标注的 dimension，二者不能张冠李戴。
反面例子：把一道「两数之和」这类通用算法题标注为「Redis 缓存应用」。
如果某个维度你一时想不到好题，就出该维度下更基础的题，
也不要把无关题目挂到它下面充数。

返回格式：{"questions":[{"type":1,"content":"...","difficulty":2,"dimension":"..."}]}
"""

CRITIC_SYS = """你是一位严格的面试题质量审核员。
你的任务是审核一批面试题是否合格，而不是重新出题。

审核标准：
1. **维度一致性（最优先，必须严查）**：每道题的 content 是否真正属于它标注的
   dimension。张冠李戴必须判不通过——例如把「两数之和」这类通用算法题
   标注为「Redis 缓存应用」，就是典型的不通过项。
2. 覆盖度：JD 中的核心技术点是否都被覆盖到
3. 去重：是否存在两道题在考察同一个技术点
4. 具体性：是否存在空泛到无法作答的题目
5. 难度分布：是否至少有中等及以上难度的题

判断尺度：除维度一致性必须严查外，其余方面宁可放过、不要苛刻。
例如题目略简单、表述稍啰嗦，都不应判不通过；只有实质缺陷才判不通过。
这样既拦住真问题，又避免为了追求完美而反复重出、浪费成本。

要求：
1. passed 为 true 表示通过，false 表示需要修订
2. issues 逐条列出具体问题，要指出是第几题，便于定向修订
3. missingDimensions 列出 JD 提到但没被覆盖的维度
4. 只返回 JSON，不要任何解释文字

返回格式：{"passed":true,"reason":"...","issues":[],"missingDimensions":[]}
"""

REVISER_SYS = """你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
你收到了一批初稿面试题以及审核意见，需要按审核意见定向修订。

要求：
1. 只修改审核意见指出的问题，没有问题的题目尽量保持原样
2. 审核意见指出缺失的维度，必须补上对应的新题目
3. 保持题目总数为 5 道，dimension 字段仍须填写
4. 返回修订后的完整题目列表（不是增量）
5. 只返回 JSON，不要任何解释文字

返回格式：{"questions":[{"type":1,"content":"...","difficulty":2,"dimension":"..."}]}
"""


def run_baseline(jd: str):
    raw = chat(BASELINE_SYS, "JD：\n%s\n\n请生成 5 道面试题。" % jd)
    data = parse_json(raw)
    return (data or {}).get("questions") or []


def run_agentloop(jd: str):
    """复刻 InterviewAgentLoop.run：Planner → Executor → Critic → 定向修订（最多 2 轮）"""
    trace = {"planner_degraded": False, "critic_degraded": False,
             "rounds": 1, "critics": []}

    # Planner（失败则降级：plan 为空串）
    plan_text = ""
    try:
        raw = chat(PLANNER_SYS, "请拆解以下 JD 的考察维度：\n\n%s" % jd)
        plan = parse_json(raw)
        if plan and plan.get("dimensions"):
            plan_text = json.dumps(plan, ensure_ascii=False)
        else:
            trace["planner_degraded"] = True
    except Exception:                              # noqa: BLE001
        trace["planner_degraded"] = True

    # Executor 首轮
    raw = chat(EXECUTOR_SYS,
               "JD：\n%s\n\n考察计划：\n%s\n\n请按考察计划出题。若考察计划为空，请自行均衡覆盖 JD 中的技术栈。"
               % (jd, plan_text))
    draft = (parse_json(raw) or {}).get("questions") or []
    first_draft = list(draft)          # 留一份首轮快照，用于评估「Critic+修订」的增量价值

    # Critic + 定向修订（最多 2 轮）
    rounds = 1
    while rounds < 2:
        try:
            raw = chat(CRITIC_SYS,
                       "JD：\n%s\n\n待审核题目：\n%s\n\n请给出审核结论。"
                       % (jd, json.dumps({"questions": draft}, ensure_ascii=False)))
            crit = parse_json(raw)
        except Exception:                          # noqa: BLE001
            trace["critic_degraded"] = True
            break
        if not crit:
            trace["critic_degraded"] = True
            break
        trace["critics"].append(crit)
        if crit.get("passed") is None or crit.get("passed") is True:
            break
        # 定向修订
        try:
            raw = chat(REVISER_SYS,
                       "JD：\n%s\n\n考察计划：\n%s\n\n初稿题目：\n%s\n\n审核意见：\n%s\n\n请输出修订后的完整题目列表。"
                       % (jd, plan_text,
                          json.dumps({"questions": draft}, ensure_ascii=False),
                          json.dumps(crit, ensure_ascii=False)))
            revised = (parse_json(raw) or {}).get("questions") or []
        except Exception:                          # noqa: BLE001
            break
        if revised:
            draft = revised
            rounds += 1
        else:
            break                                   # 修订为空 → 保留上一版
    trace["rounds"] = rounds
    return first_draft, draft, trace


# ================= 裁判：抽 JD 技术点 / 判维度一致性 / 判覆盖度 =================
JUDGE_EXTRACT_SYS = """你是技术招聘专家。从下面的 JD 中抽取核心技术点。
要求：
1. 只抽 JD 里真实出现的技术名词（框架/中间件/语言特性/方法论），不要脑补
2. 每个技术点 2-6 个字，如「SpringBoot」「MySQL 索引」「Redis 缓存」
3. 数量 4-8 个
4. 只返回 JSON：{"points":["...","..."]}
"""

JUDGE_CONSISTENCY_SYS = """你是面试题质量裁判。我会给你若干道题，每道题带有 content（题目内容）与 dimension（宣称考察的维度）。

请逐题判断：这道题的 content 是否**真正**在考察它标注的 dimension？
- 只有在题目确实围绕该维度展开时才判 true
- 若题目是通用题（如通用算法题、泛泛的"介绍一下 X"）与维度无关，判 false
- 若题目实际考察的是另一个维度（张冠李戴），判 false
-  borderline 情况从严：拿不准就判 false

只返回 JSON：{"results":[{"index":0,"consistent":true,"reason":"一句话"}]}
"""

JUDGE_COVERAGE_SYS = """你是技术招聘专家。我会给你一份 JD 的技术点清单，以及一组面试题（含每道题的 dimension）。

请判断：清单里的每个技术点，是否被这组题目**实质覆盖**？
- 「提到过名字但没问实质内容」不算覆盖
- 题目必须真的在考察该技术点才算覆盖

只返回 JSON：{"covered":["被覆盖的技术点原样列出"]}
"""


def judge_extract_points(jd: str):
    raw = chat(JUDGE_EXTRACT_SYS, "JD：\n%s" % jd, temperature=0)
    d = parse_json(raw)
    return (d or {}).get("points") or []


def judge_consistency(questions):
    """返回 (一致题数, 总题数, 明细)"""
    if not questions:
        return 0, 0, []
    payload = json.dumps(
        [{"index": i, "content": q.get("content", ""), "dimension": q.get("dimension", "")}
         for i, q in enumerate(questions)], ensure_ascii=False)
    raw = chat(JUDGE_CONSISTENCY_SYS, "题目列表：\n%s" % payload, temperature=0)
    d = parse_json(raw) or {}
    results = d.get("results") or []
    ok = sum(1 for r in results if r.get("consistent") is True)
    return ok, len(questions), results


def judge_coverage(jd: str, points, questions):
    """返回 (覆盖点数和, 总点数)"""
    if not points:
        return 0, 0
    payload = json.dumps({"questions": [{"content": q.get("content", ""),
                                         "dimension": q.get("dimension", "")}
                                        for q in questions]}, ensure_ascii=False)
    raw = chat(JUDGE_COVERAGE_SYS,
               "JD：\n%s\n\n技术点清单：\n%s\n\n题目：\n%s"
               % (jd, json.dumps(points, ensure_ascii=False), payload), temperature=0)
    d = parse_json(raw) or {}
    covered = d.get("covered") or []
    return len(covered), len(points)


# ================= 主流程 =================
def evaluate_one(idx_jd):
    idx, jd = idx_jd
    points = judge_extract_points(jd)

    base_q = run_baseline(jd)
    draft_q, final_q, trace = run_agentloop(jd)

    def pack(qs, with_consistency):
        c_ok, c_tot, _ = judge_consistency(qs) if with_consistency else (0, 0, [])
        cov_ok, cov_tot = judge_coverage(jd, points, qs)
        return {"consist_ok": c_ok, "consist_tot": c_tot,
                "cov_ok": cov_ok, "cov_tot": cov_tot, "n": len(qs)}

    return {
        "idx": idx,
        "jd": jd,
        "points": points,
        # baseline 无 dimension 字段 → 一致性不适用（N/A）
        "baseline": pack(base_q, with_consistency=False),
        "loop_draft": pack(draft_q, with_consistency=True),   # Critic 修订前
        "loop_final": pack(final_q, with_consistency=True),   # Critic 修订后（真实交付）
        "trace": trace,
    }


def main():
    n = len(JD_CORPUS)
    if "--jd" in sys.argv:
        n = int(sys.argv[sys.argv.index("--jd") + 1])
    corpus = list(enumerate(JD_CORPUS[:n]))
    print("评测集：%d 条 JD，baseline vs agentloop 双跑 + 独立裁判\n" % len(corpus))

    results = []
    with ThreadPoolExecutor(max_workers=4) as ex:
        futs = {ex.submit(evaluate_one, c): c[0] for c in corpus}
        for f in as_completed(futs):
            try:
                results.append(f.result())
                print("  ✓ JD#%d 完成" % futs[f])
            except Exception as e:                 # noqa: BLE001
                print("  ✗ JD#%d 失败: %s" % (futs[f], e))
    results.sort(key=lambda r: r["idx"])

    def agg(key):
        return (sum(r[key]["consist_ok"] for r in results),
                sum(r[key]["consist_tot"] for r in results),
                sum(r[key]["cov_ok"] for r in results),
                sum(r[key]["cov_tot"] for r in results))

    bo, bt, bc, bp = agg("baseline")
    do, dt, dc, dp = agg("loop_draft")
    fo, ft, fc, fp = agg("loop_final")

    def pct(a, b):
        return 100.0 * a / b if b else 0.0

    d_cons, f_cons = pct(do, dt), pct(fo, ft)
    b_cov, d_cov, f_cov = pct(bc, bp), pct(dc, dp), pct(fc, fp)

    revised = sum(1 for r in results if r["trace"]["rounds"] > 1)
    crit_fail = sum(1 for r in results if r["trace"]["critic_degraded"])
    plan_fail = sum(1 for r in results if r["trace"]["planner_degraded"])

    print("\n" + "=" * 74)
    print("评测结果（样本 %d 条 JD，共 %d 道题）" % (len(results), ft))
    print("=" * 74)
    print("| 指标 | baseline | AgentLoop-修订前 | AgentLoop-最终 | 结论 |")
    print("|---|---|---|---|---|")
    print("| 维度一致性 | N/A（无 dimension） | %.1f%% (%d/%d) | **%.1f%% (%d/%d)** | Critic 修订 %+.1f pp |"
          % (d_cons, do, dt, f_cons, fo, ft, f_cons - d_cons))
    print("| 覆盖度 | %.1f%% (%d/%d) | %.1f%% (%d/%d) | **%.1f%% (%d/%d)** | vs baseline %+.1f pp |"
          % (b_cov, bc, bp, d_cov, dc, dp, f_cov, fc, fp, f_cov - b_cov))
    print("\n过程指标：")
    print("  触发定向修订：%d/%d (%.0f%%)" % (revised, len(results), 100.0 * revised / len(results)))
    print("  Critic 降级(fail-open)：%d 次" % crit_fail)
    print("  Planner 降级：%d 次" % plan_fail)
    print("=" * 74)

    out = {"sample": len(results),
           "dimension_consistency": {"baseline": None,
                                     "loop_draft": round(d_cons, 1),
                                     "loop_final": round(f_cons, 1),
                                     "draft_raw": [do, dt], "final_raw": [fo, ft]},
           "coverage": {"baseline": round(b_cov, 1),
                        "loop_draft": round(d_cov, 1),
                        "loop_final": round(f_cov, 1),
                        "baseline_raw": [bc, bp], "final_raw": [fc, fp]},
           "process": {"revised": revised, "critic_degraded": crit_fail,
                       "planner_degraded": plan_fail},
           "details": results}
    with open("bench/eval-result.json", "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
    print("\n明细已写入 bench/eval-result.json")


if __name__ == "__main__":
    main()
