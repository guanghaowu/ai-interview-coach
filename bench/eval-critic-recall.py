#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Critic 有效性验证（异常注入 / 召回率测试）
=========================================
背景：eval-agentloop.py 跑完发现 Critic 在 8 条正常 JD 上 100% 判通过，
定向修订从未触发。这有两种可能：
  (A) Critic 尺度太松，形同虚设 —— 设计失败
  (B) 正常题目质量本来就过关，Critic 只是没机会动作 —— 它是「保险丝」

区分方法：手工构造一批**确定有缺陷**的题目喂给 Critic，
看它能否判 passed=false 并指出问题。这测的是 Critic 的**召回率**。

若召回率高 → 结论 (B)，AgentLoop 的定位是「兜底保险」而非「日常提分」；
若召回率低 → 结论 (A)，Critic prompt 需要收紧。

用法：export DEEPSEEK_API_KEY=sk-xxx && python eval-critic-recall.py
"""

import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

API_KEY = os.environ.get("DEEPSEEK_API_KEY")
if not API_KEY:
    sys.exit("请先 export DEEPSEEK_API_KEY=sk-xxx")
BASE_URL = "https://api.deepseek.com/v1"

# Critic prompt —— 与 InterviewAiService.java 完全一致
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

JD = "招聘 Java 后端实习生，熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ 与高并发设计"

# 8 组缺陷样本：expected=False 表示「正确做法是判不通过」
CASES = [
    {"name": "① 维度张冠李戴（算法题挂 Redis）", "expected": False, "questions": [
        {"type": 1, "content": "给定一个整数数组和一个目标值，请找出数组中和为目标值的两个整数，返回下标。",
         "difficulty": 2, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "SpringBoot 的自动配置原理是什么？", "difficulty": 2, "dimension": "SpringBoot 框架"},
        {"type": 2, "content": "MySQL 联合索引的最左前缀原则是什么，举例说明。", "difficulty": 2, "dimension": "MySQL 索引优化"},
        {"type": 3, "content": "如何保证 Redis 与 MySQL 的数据一致性？", "difficulty": 3, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "RocketMQ 如何保证消息不丢失？", "difficulty": 2, "dimension": "RocketMQ 消息队列"}]},

    {"name": "② 两题重复考同一技术点", "expected": False, "questions": [
        {"type": 4, "content": "什么是缓存穿透？如何解决？", "difficulty": 2, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "什么是缓存击穿？和缓存穿透有什么区别？", "difficulty": 2, "dimension": "Redis 缓存应用"},
        {"type": 2, "content": "Redis 缓存穿透的常见解决方案有哪些？请比较布隆过滤器与空值缓存。",
         "difficulty": 3, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "SpringBoot  starter 的原理是什么？", "difficulty": 2, "dimension": "SpringBoot 框架"},
        {"type": 4, "content": "MySQL 索引失效的常见场景有哪些？", "difficulty": 2, "dimension": "MySQL 索引优化"}]},

    {"name": "③ 空泛到无法作答", "expected": False, "questions": [
        {"type": 4, "content": "请介绍一下 Java。", "difficulty": 1, "dimension": "Java 基础"},
        {"type": 4, "content": "说说你对 SpringBoot 的理解。", "difficulty": 1, "dimension": "SpringBoot 框架"},
        {"type": 4, "content": "MySQL 怎么优化？", "difficulty": 1, "dimension": "MySQL 索引优化"},
        {"type": 4, "content": "Redis 有什么用？", "difficulty": 1, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "消息队列是什么？", "difficulty": 1, "dimension": "RocketMQ 消息队列"}]},

    {"name": "④ 缺失 JD 核心维度（无 RocketMQ）", "expected": False, "questions": [
        {"type": 4, "content": "SpringBoot 自动配置的核心注解与加载流程是什么？", "difficulty": 2, "dimension": "SpringBoot 框架"},
        {"type": 2, "content": "一条慢 SQL 你会如何排查与优化？", "difficulty": 3, "dimension": "MySQL 索引优化"},
        {"type": 4, "content": "MySQL 索引失效的常见场景有哪些？", "difficulty": 2, "dimension": "MySQL 索引优化"},
        {"type": 2, "content": "高并发下如何用 Redis 做库存扣减？", "difficulty": 3, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "SpringBoot 内嵌 Tomcat 的启动流程是怎样的？", "difficulty": 2, "dimension": "SpringBoot 框架"}]},

    {"name": "⑤ 全是简单题（无中等及以上）", "expected": False, "questions": [
        {"type": 4, "content": "SpringBoot 的启动类上有哪些注解？", "difficulty": 1, "dimension": "SpringBoot 框架"},
        {"type": 4, "content": "MySQL 主键索引和唯一索引有什么区别？", "difficulty": 1, "dimension": "MySQL 索引优化"},
        {"type": 4, "content": "Redis 有哪几种数据类型？", "difficulty": 1, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "RocketMQ 有哪几种消息发送方式？", "difficulty": 1, "dimension": "RocketMQ 消息队列"},
        {"type": 4, "content": "线程池的核心参数有哪些？", "difficulty": 1, "dimension": "高并发设计"}]},

    {"name": "⑥ 题目偏离 JD（问前端）", "expected": False, "questions": [
        {"type": 4, "content": "Vue3 的响应式原理与 Vue2 有什么区别？", "difficulty": 2, "dimension": "前端框架"},
        {"type": 4, "content": "CSS 中 flex 布局的主轴和交叉轴如何设置？", "difficulty": 2, "dimension": "前端框架"},
        {"type": 4, "content": "JavaScript 事件循环机制是怎样的？", "difficulty": 2, "dimension": "前端框架"},
        {"type": 4, "content": "SpringBoot 自动配置原理是什么？", "difficulty": 2, "dimension": "SpringBoot 框架"},
        {"type": 4, "content": "MySQL 索引最左前缀原则是什么？", "difficulty": 2, "dimension": "MySQL 索引优化"}]},

    # ---- 对照组：确实合格的题目，正确做法是判通过 ----
    {"name": "⑦ 对照组-合格（应判通过）", "expected": True, "questions": [
        {"type": 4, "content": "SpringBoot 自动配置的核心流程是什么？@SpringBootApplication 由哪些注解组成？",
         "difficulty": 2, "dimension": "SpringBoot 框架"},
        {"type": 2, "content": "一条执行 5 秒的慢 SQL，你会如何定位并优化？请说明 explain 的关键字段。",
         "difficulty": 3, "dimension": "MySQL 索引优化"},
        {"type": 4, "content": "缓存穿透、击穿、雪崩的区别与各自解决方案是什么？", "difficulty": 2, "dimension": "Redis 缓存应用"},
        {"type": 2, "content": "如何用 RocketMQ 实现分布式事务最终一致性？", "difficulty": 3, "dimension": "RocketMQ 消息队列"},
        {"type": 3, "content": "设计一个秒杀接口，你会从哪些层面做限流与削峰？", "difficulty": 3, "dimension": "高并发设计"}]},

    {"name": "⑧ 对照组-合格但略啰嗦（应放过）", "expected": True, "questions": [
        {"type": 4, "content": "请你详细地说一说 SpringBoot 这个框架里面自动配置这个功能它大概是怎么一回事，就是它为什么能自动帮我们把 Bean 给配置好，中间经过了哪些步骤呢？",
         "difficulty": 2, "dimension": "SpringBoot 框架"},
        {"type": 4, "content": "MySQL 里面的索引，我们知道它有很多种，那么请你谈一谈在日常工作当中，有哪些情况会导致我们建立的索引它实际上并没有被使用到，也就是所谓的索引失效呢？",
         "difficulty": 2, "dimension": "MySQL 索引优化"},
        {"type": 4, "content": "Redis 作为缓存中间件，我们在使用它的时候，经常会遇到一些关于缓存的问题，请你说说缓存穿透是怎么一回事，另外还有哪些类似的问题需要我们注意呢？",
         "difficulty": 2, "dimension": "Redis 缓存应用"},
        {"type": 4, "content": "RocketMQ 这个消息队列中间件，它在保证消息可靠性方面做了哪些事情，消息从生产者发送到最终被消费者消费，这个过程中有哪些环节可能会出现消息丢失？",
         "difficulty": 3, "dimension": "RocketMQ 消息队列"},
        {"type": 3, "content": "如果让你来设计一个可以支撑高并发的接口，你会从哪些方面去考虑和设计，比如说在系统架构或者代码层面你会采取什么样的措施来保证系统不会被流量打垮？",
         "difficulty": 3, "dimension": "高并发设计"}]},
]


def chat(system, user, temperature=0.7, max_tokens=2000, retries=3):
    payload = {"model": "deepseek-chat",
               "messages": [{"role": "system", "content": system},
                            {"role": "user", "content": user}],
               "temperature": temperature, "max_tokens": max_tokens}
    req = urllib.request.Request(BASE_URL + "/chat/completions",
                                 data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + API_KEY},
                                 method="POST")
    last = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))["choices"][0]["message"]["content"]
        except Exception as e:                      # noqa: BLE001
            last = e
    raise RuntimeError(str(last))


def parse_json(t):
    s = t.strip()
    if s.startswith("```"):
        s = s.split("```")[1] if s.count("```") >= 2 else s[3:]
        if s.startswith("json"):
            s = s[4:]
    try:
        return json.loads(s.strip())
    except Exception:                              # noqa: BLE001
        return None


def run(case):
    raw = chat(CRITIC_SYS, "JD：\n%s\n\n待审核题目：\n%s\n\n请给出审核结论。"
               % (JD, json.dumps({"questions": case["questions"]}, ensure_ascii=False)))
    c = parse_json(raw)
    return case, c


def main():
    print("Critic 异常注入测试：%d 组（6 组有缺陷 + 2 组合格对照）\n" % len(CASES))
    rows = []
    with ThreadPoolExecutor(max_workers=4) as ex:
        futs = [ex.submit(run, c) for c in CASES]
        for f in as_completed(futs):
            case, c = f.result()
            got = (c or {}).get("passed")
            ok = (got == case["expected"])
            rows.append((case["name"], case["expected"], got, ok,
                         (c or {}).get("issues") or [], (c or {}).get("missingDimensions") or []))
    rows.sort(key=lambda r: r[0])

    tp = sum(1 for r in rows if r[1] is False and r[2] is False)   # 缺陷被抓出
    fn = sum(1 for r in rows if r[1] is False and r[2] is not False)  # 缺陷被漏掉
    tn = sum(1 for r in rows if r[1] is True and r[2] is True)
    fp = sum(1 for r in rows if r[1] is True and r[2] is False)

    print("| 样本 | 应判 | 实判 | 是否正确 |")
    print("|---|---|---|---|")
    for name, exp, got, ok, issues, missing in rows:
        print("| %s | %s | %s | %s |" % (name, "通过" if exp else "不通过",
                                         "通过" if got else "不通过", "✅" if ok else "❌"))
        if issues:
            print("     └ issues: %s" % "; ".join(issues[:2]))
        if missing:
            print("     └ missing: %s" % missing)

    recall = 100.0 * tp / (tp + fn) if (tp + fn) else 0
    fpr = 100.0 * fp / (fp + tn) if (fp + tn) else 0
    print("\n" + "=" * 60)
    print("缺陷召回率 Recall = %d/%d = %.0f%%" % (tp, tp + fn, recall))
    print("误杀率     FPR    = %d/%d = %.0f%%" % (fp, fp + tn, fpr))
    print("=" * 60)

    with open("bench/eval-critic-recall.json", "w", encoding="utf-8") as fh:
        json.dump({"recall": recall, "fpr": fpr, "tp": tp, "fn": fn, "tn": tn, "fp": fp,
                   "rows": [{"name": r[0], "expected": r[1], "got": r[2], "ok": r[3],
                             "issues": r[4], "missing": r[5]} for r in rows]},
                  fh, ensure_ascii=False, indent=2)
    print("明细：bench/eval-critic-recall.json")


if __name__ == "__main__":
    main()
