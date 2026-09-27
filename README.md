# AiInterviewCoach —— AI 面试模拟平台

> 输入岗位 JD → AI 自动生成面试题 → 作答 → AI 评分并给出改进建议，完整模拟一场技术面试。

![Java](https://img.shields.io/badge/Java-21-blue)
![SpringBoot](https://img.shields.io/badge/SpringBoot-3.2.5-green)
![MySQL](https://img.shields.io/badge/MySQL-8.0-orange)
![Redis](https://img.shields.io/badge/Redis-7-red)
![RocketMQ](https://img.shields.io/badge/RocketMQ-5.3.1-purple)
![Vue](https://img.shields.io/badge/Vue-3.5-42b883)

---

## 一、项目简介

求职者练习面试时普遍面临三个问题：**没人出题、不知道答得好不好、题目和岗位不匹配**。

AiInterviewCoach 用大模型解决这三点：粘贴一段 JD，系统自动解析岗位要求、生成 3-5 道针对性面试题；
用户作答后，AI 从准确性、深度、完整性三个维度评分，并给出具体的优点、缺点和改进建议。

**核心挑战不在业务逻辑，而在于**：AI 接口单次调用耗时 10-30 秒，同步调用会直接打满 Tomcat 线程池。
因此项目围绕「**异步化 + 成本控制 + 稳定性**」做架构设计。

项目含**完整前后端**：前端 Vue 3 + Element Plus（5 个页面：登录注册 / 会话列表 / 新建面试 / 答题评分 / 个人中心），
由 nginx 托管并反向代理后端，一条 `docker compose up -d` 即可全栈启动。

---

## 二、技术栈

| 分类 | 技术 |
|---|---|
| 运行时 | Java 21 LTS（Spring Boot 3.2 基线是 17，主动升到 21；**虚拟线程实测后未启用**，原因见压测报告 §3.6） |
| 框架 | SpringBoot 3.2.5、Spring AOP |
| 持久层 | MyBatis-Plus 3.5.9、Druid 连接池、MySQL 8.0 |
| 缓存 / 限流 | Redis 7（响应缓存、会话记忆、令牌桶限流、幂等） |
| 消息队列 | RocketMQ 5.3.1（AI 调用异步化、失败重试、死信兜底） |
| 高可用 | Resilience4j 2.2.0（LLM 调用熔断）+ 失败任务表重投 |
| 大模型 | LangChain4j 0.36.2 + DeepSeek（OpenAI 兼容协议） |
| 安全 | JWT（JJWT 0.12.5）+ BCrypt 密码加密 |
| 前端 | Vue 3.5 + Vite 5 + Vue Router + Pinia + Axios + Element Plus |
| 接口文档 | Knife4j 4.5.0（OpenAPI3 / springdoc 2.3.0） |
| 部署 | Docker 多阶段构建 + Docker Compose（nginx 托管前端 + 反代后端） |
| 工具 | Hutool、Lombok |

---

## 三、系统架构

```
  浏览器 · Vue 3 SPA（nginx 托管，http://localhost:8081）
  所有请求走相对路径 /api/** ──nginx 反向代理──▶ ai-coach-app:8080
                    │
                    ▼
                    ┌──────────────────────────────────────┐
   POST /create ───▶│  InterviewController                 │
   (创建会话)        │    └─ @RateLimit 令牌桶限流(10次/天)   │
                    └──────────────┬───────────────────────┘
                                   │ ① 创建会话(status=0 出题中)
                                   │ ② 投递 MQ 消息
                                   ▼
                    ┌──────────────────────────────────────┐
                    │  InterviewProducer → RocketMQ         │
                    └──────────────┬───────────────────────┘
                                   │ ③ 异步消费
                                   ▼
                    ┌──────────────────────────────────────┐
                    │  InterviewConsumer                    │
                    │    ├─ 幂等检查(ai_call_log)            │
                    │    ├─ 指数退避重试 1s→2s→4s            │
                    │    └─ LangChain4j → DeepSeek          │
                    │         (Function Calling 工具)        │
                    └──────────────┬───────────────────────┘
                                   │ ④ 题目落库 + 更新 status=1
                                   ▼
   GET /{id} ──────▶  前端轮询拿结果
```

**为什么这样设计**：主接口只做「建会话 + 投消息」，**1 秒内返回**；
AI 调用放到 MQ 消费端异步执行，前端通过轮询获取结果。避免 HTTP 线程被长耗时 AI 调用占满。

**前端为什么用 nginx 反代而不是直连后端**：前端所有请求写相对路径 `/api/**`，
开发期由 Vite dev server 代理、生产期由 nginx 代理，两种环境代码零改动；
同时因为同源，**天然没有跨域问题**，后端无需配置 CORS。

---

## 四、核心功能

| 功能 | 说明 |
|---|---|
| 用户体系 | 注册 / 登录 / JWT 鉴权 / BCrypt 密码加密 |
| **AgentLoop 出题** | Planner 拆考察维度 → Executor 按维度出题 → Critic 审核 → 不合格定向修订（最多两轮） |
| AI 出题 | 提交 JD → 生成 5 道题（题型混合：八股 / 场景 / 编程 / 项目），每题带考察维度 |
| AI 评分 | 提交回答 → 1-10 分 + 优点 / 缺点 / 改进建议 |
| 会话记忆 | Redis 存对话历史（TTL 24h，保留最近 20 条） |
| 使用配额 | 每用户每天 10 次 AI 调用（Redis 令牌桶） |
| 结果复用 | **同一用户**提交相同 JD 时复用其历史题目，节省 Token（按 userId 隔离，不会串到他人） |
| 会话管理 | 我的会话列表（分页，含题目数 / 已答数）+ 会话完整详情（题目 / 每题最新回答 / 评分反馈） |
| **Web 前端** | Vue 3 SPA：登录注册 / 会话列表 / 新建面试（实时出题进度）/ 答题评分（维度标签 + 评分反馈）/ 个人中心 |

---

## 五、接口清单

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/user/register` | 否 | 注册，返回 JWT |
| POST | `/api/user/login` | 否 | 登录，返回 JWT |
| GET | `/api/user/info` | 是 | 当前用户信息 |
| PUT | `/api/user/info` | 是 | 修改昵称 / 头像 |
| POST | `/api/interview/create` | 是 | 创建会话（AI 异步出题，限流 10 次/天） |
| GET | `/api/interview/{sessionId}` | 是 | 会话详情（含题目），轮询出题结果 |
| GET | `/api/interview/sessions` | 是 | 我的会话列表（分页，含题目数 / 已答数） |
| GET | `/api/interview/sessions/{sessionId}` | 是 | 会话完整详情（题目 + 每题最新回答 + 评分反馈） |
| POST | `/api/interview/answer` | 是 | 提交回答（AI 异步评分，限流 10 次/天） |
| GET | `/api/interview/answer/{answerId}` | 是 | 轮询评分结果 |
| POST | `/api/interview/{sessionId}/retry` | 是 | 手动重试出题（仅失败态可用，限流 5 次/天） |
| GET | `/api/health` | 否 | 健康检查（含 LLM 熔断器状态） |

**会话状态**：`0` = AI 出题中 ｜ `1` = 已完成 ｜ `2` = 失败
**回答状态**：`0` = 待评分 ｜ `1` = 已评分 ｜ `2` = 评分失败

> 两个 AI 接口都是「提交即返回 + 前端轮询」的异步模型：
> 提交只拿到 id 和 `status=0`，真正的结果靠轮询接口取。

### 在线接口文档

启动后访问 **http://localhost:8080/doc.html**（Knife4j）。
点右上角「Authorize」填入登录返回的 token，即可直接调试需要鉴权的接口，无需手写 curl。

### 调用示例

```bash
# 1. 注册
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"123456"}'

# 2. 登录拿 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"123456"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 3. 创建会话（立即返回，AI 异步出题）
curl -X POST http://localhost:8080/api/interview/create \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"jdContent":"招聘 Java 后端实习生，熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ"}'

# 4. 轮询拿结果（status=1 表示出题完成）
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/interview/1

# 5. 我的会话列表（分页）
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/interview/sessions?page=1&size=10"

# 6. 会话完整详情（题目 + 每题最新回答 + 评分反馈）
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/interview/sessions/1

# 7. 修改昵称 / 头像
curl -X PUT http://localhost:8080/api/user/info \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"nickname":"面试练习生"}'
```

---

## 六、快速开始

### 方式一：Docker 一键部署（推荐）

```bash
# 1. 配置 API Key（不要提交到 git）
#    在 src/main/resources/ 新建 application-local.yml：
#    langchain4j:
#      open-ai:
#        chat-model:
#          api-key: sk-你的DeepSeekKey

# 2. 启动全部服务（MySQL + Redis + RocketMQ + 后端 + 前端 nginx）
docker compose up -d

# 3. 建库建表（首次）
docker exec -i ai-coach-mysql mysql -uroot -p123456 < src/main/resources/sql/init.sql

# 4. 验证
curl http://localhost:8080/api/health

# 5. 打开前端
#    浏览器访问 http://localhost:8081
```

> 首次构建前端镜像会执行 `npm ci && npm run build`，需要几分钟；之后有层缓存会很快。

### 方式二：本地开发

```bash
# 依赖
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker

# 建表
docker exec -i ai-coach-mysql mysql -uroot -p123456 < src/main/resources/sql/init.sql

# 编译（项目自带 Maven Wrapper，无需预装 Maven）
./mvnw -B clean compile

# 启动（显式指定端口 + 跳过测试编译）
SERVER_PORT=8080 ./mvnw spring-boot:run -Dmaven.test.skip=true
```

前端本地开发（另开一个终端）：

```bash
cd client
npm install
npm run dev     # http://localhost:5173，已配置 /api 代理到 127.0.0.1:8080
```

前端构建产物：

```bash
cd client
npm run build   # 输出到 client/dist
```

---

## 七、技术亮点

### 1. AgentLoop 三角色编排出题（Planner → Executor → Critic）
出题不是「一次模型调用直接要 5 道题」，而是一个带闭环校验的 Agent。原因很直接：
单次调用时模型很容易把几道题挤在同一个技术点上（比如全是 Redis），JD 里的其他技术点
没人问，而且题目质量完全依赖那一次输出的运气，没有任何复核。

| 角色 | 职责 |
|---|---|
| **Planner** | 读 JD → 拆出 3-5 个考察维度（维度名 + 题量 + 考察重点），各维度题量之和为 5 |
| **Executor** | 按考察计划出题，每道题标注所属维度（`dimension` 字段） |
| **Critic** | 审核覆盖度 / 是否重复 / 是否空泛 / 难度分布；不通过则**带着具体问题定向修订**，最多两轮 |

**为什么最多两轮**：每一轮都是真实的模型调用（Token + 10-30 秒延迟），轮次无上限意味着
延迟与成本都不可控，而收益递减——第一轮修订通常已解决主要问题。用硬上限把最坏情况钉死：
最坏 2 次 Executor + 1 次 Planner + 1 次 Critic。

**降级策略（这部分比正常路径更重要）**：

| 环节失败 | 处理 | 理由 |
|---|---|---|
| Planner 失败 | 传空计划继续，让 Executor 自行均衡覆盖 | 规划是锦上添花，不该成为出题的硬依赖 |
| Critic 失败 / 超时 | **视为通过（fail-open）** | 审核是质量增强，不是正确性依赖；不能拿可用性换非必需的东西 |
| Executor 失败 | 抛出，交给 MQ 重试 | 这是真正的失败 |
| 修订返回空 | 保留上一版题目 | 第二轮改坏了不能把第一轮的成果也丢掉 |

**可观测性**：`interview_session.agent_rounds` 记录实际执行轮数
（0=复用历史题目 / 1=首轮通过 / 2=修订过一次），`question.dimension` 记录考察维度。
出题过程因此是可解释的——前端能按维度分组展示，也能回答「这次 Agent 跑了几轮、为什么改题」。

> 踩坑记录：`CritiqueDTO.passed` 必须用包装类型 `Boolean`。用基本类型 `boolean` 时，
> 模型漏返回该字段会被反序列化成 `false`，导致本来合格的题目被反复重出、白烧两轮 Token。

### 2. RocketMQ 异步化，主接口秒级返回
AI 出题耗时 10-30 秒。同步调用会占满 Tomcat 线程池，并发稍高就阻塞整个服务。
改为「建会话 + 投 MQ」后主接口立即返回，AI 调用在消费端执行，前端轮询取结果。

### 3. 指数退避重试 + 幂等去重
三方 API 存在网络抖动。失败后按 **1s → 2s → 4s** 退避重试（最多 3 次）；
同时用 `ai_call_log` 表以 `(userId, jdMd5)` 为唯一键做幂等（**按用户维度**，不会串到他人），
避免 MQ 重投导致重复出题。

> 踩坑记录：幂等标记的 insert 必须捕获唯一键冲突。否则并发/重投时异常会冒到外层 catch，
> 把「已经出题成功」的会话无条件改判成「失败」，前端能看到状态从 1 抖到 2。
> 同理，失败兜底逻辑要先判断当前是否已是完成态，不能无脑覆盖。

### 4. Redis 令牌桶限流（Lua 原子执行）
每用户每天 10 次 AI 调用配额，用 Lua 脚本保证「取令牌 + 回写」的原子性。

> 踩坑记录：速率计算必须用 `double`。若用 `long`，`10/86400` 取整为 `0`、被兜底成 `1`，
> 会导致补充速率大于消耗速率，**限流彻底失效**。

### 5. Function Calling 获取真实上下文
通过 LangChain4j `@Tool` 暴露两个工具（查询会话题目、查询题目原文），
让模型评分时能主动调用工具获取上下文，而不是凭空编造。

### 6. Redis 会话记忆
用 Redis List 存对话历史（`interview:memory:{sessionId}`），TTL 24h，自动 trim 到最近 20 条，
支持多轮追问场景。

### 7. Docker 多阶段构建
构建阶段用 Maven 镜像编译，运行阶段只保留 JRE（alpine），显著减小镜像体积；
`COPY pom.xml` 单独下载依赖以利用 Docker 层缓存。

### 8. 事务边界设计
事务全部收在 `SessionService` / `FeedbackService` 两个独立 Bean 里；编排层
（`InterviewServiceImpl`）**刻意不加 `@Transactional`** —— 既避免 private 方法自调用
导致注解静默失效，也让每个方法的事务语义一目了然。三条原则：

- **写操作原子化**：`copyQuestionsAndMarkDone` 把「复制题目 + 置为完成」放进同一事务，
  中途失败整体回滚，杜绝「题目不全却已完成」的会话；
- **长耗时调用留在事务外**：AI 评分耗时 5-15 秒，绝不能进事务，否则一个请求就占着数据库连接
  15 秒，连接池（max-active=20）并发 20 就爆。所以 `FeedbackService.saveGradingResult`
  只包住「插 feedback + 回填 answer」两条写操作；
- **MQ 投递放在事务提交之后**：若在事务内投递，消息可能先于提交被消费，
  消费端按 READ_COMMITTED 读不到会话行，于是题目写进去了、会话却永远停在「出题中」。
  投递失败时走补偿——把会话置为失败终态，而不是留下僵尸会话。

### 9. Knife4j 在线接口文档
集成 OpenAPI3（springdoc），`/doc.html` 可视化调试，全局配置 JWT 认证方案，
点一下 Authorize 就能带 token 调接口，省去手写 curl。

### 10. 列表接口避免 N+1
会话列表要展示每条的题目数与已答数。直觉写法是「查一页会话 → 逐条 count」，那是 1+N 次查询；
这里改成「一次 IN 查询 + 内存分组」，整页固定 2 次查询。一页最多 20 条会话、每条几道题，
数据量完全可控——用极小的内存代价换掉 N 次数据库往返。

### 11. 前端同源反代 + 统一鉴权拦截

前端所有请求写相对路径 `/api/**`，由 Vite（开发）/ nginx（生产）代理到后端，
**同源因而无跨域**，后端一行 CORS 配置都不需要。

axios 响应拦截器按后端的错误模型分两类处理：业务异常是 `HTTP 200 + code≠0`（只弹提示），
只有 JWT 拦截器失败才是 `HTTP 401`（清 token 并跳登录）。这条区分很关键——
如果按 `code===401` 跳登录，用户在登录页输错密码就会被反复踢回登录页。
另外拦截器加了 `redirecting` 重入锁，避免并发请求同时 401 时触发多次跳转。

---

### 12. 性能数据（实测，可复现）

> 完整报告与测试方法见 [`docs/性能压测报告.md`](docs/性能压测报告.md)，
> 复现命令 `bash bench/run-bench.sh`。

**读接口吞吐：加 Redis 响应缓存前 → 后（并发 50）**

| 接口 | 优化前 QPS | 优化后 QPS | 提升 | 优化前 p99 | 优化后 p99 | 降幅 |
|---|---|---|---|---|---|---|
| `GET /sessions`（列表） | 323.9 | **1477.4** | **4.56×** | 336.8ms | **73.1ms** | **−78.3%** |
| `GET /sessions/{id}`（详情） | 510.7 | **1454.8** | **2.85×** | 211.6ms | **72.0ms** | **−66.0%** |
| `GET /api/health`（基线，无 DB） | 1920.6 | 2131.5 | 1.11× | 41.9ms | 42.8ms | — |

> 基线本身两次运行间有 +11% 波动（压测机抖动），故绝对值只做量级判断；
> 列表/详情 4.6× / 2.9× 的幅度远大于波动。冷启动（穿透 DB）实测：列表 53.8ms、详情 69.4ms。

**异步化收益：用户等待 8.1 秒 → 39 毫秒**

| 指标 | 值 | 来源 |
|---|---|---|
| 同步模型下用户要等（AI 真实耗时） | **8069ms**（11 样本，5997~10375ms） | `ai_call_log.duration_ms` 埋点实测 |
| 异步化后提交延迟（冷路径，走 MQ） | **约 39ms**（5 次采样 37.3~42.1ms） | 串行 curl 计时 |
| **提升** | **约 207 倍** | |

**限流与幂等**

| 能力 | 实测结果 |
|---|---|
| 令牌桶限流 | 30 并发 → 9 通过 / **21 被拒（拦截率 70%）**，被拒请求零 DB / MQ / AI 开销 |
| JD 幂等复用 | 同一 JD 提交 9 次 → **0 次额外 AI 调用**，9 个会话各正确复制 5 道题 |

> 压测器是自研的零依赖 Node 脚本 `bench/bench.js`：因为本项目的业务错误是
> **HTTP 200 + `body.code != 0`**，通用压测工具（wrk / JMeter）只看状态码，
> 会把被限流的请求误计为成功，结论完全相反。

**缓存的两条红线**（已写成回归断言，见 `verify-full.sh` 第 `[17]` 段）：
① 缓存 key 含 `userId`，否则他人请求命中缓存会**绕过 403 归属校验**造成越权读；
② 出题中的会话（`status=0`）用 `unless` 守卫排除，否则前端会一直读到空列表。

---

### 13. 高可用：熔断降级 + 失败任务兜底

**问题**：在此之前，依赖（DeepSeek）故障时整条异步链路没有任何保护——

- LLM 调用**无熔断**：每个请求都真实发起调用并等到 60 秒超时，MQ 消费线程被长时间占满
- MQ 重试耗尽后消息进死信队列，**无人处理**：用户侧永久卡在「失败」，没有任何恢复路径

**方案（三层，各司其职）**：

| 层 | 机制 | 关键设计 |
|---|---|---|
| 快速失败 | Resilience4j 熔断（`GuardedInterviewAiService` 装饰器） | 窗口 10 次、最少 5 次开始统计、失败率 ≥50% 打开、打开 30s、半开放 3 次 |
| 抖动重试 | `RetryUtil` 指数退避 1s→2s→4s；**熔断打开时直接放弃重试** | 不在注定失败的调用上白等退避时间 |
| 兜底恢复 | MQ 重试 3 次 → 死信 → 落 `failed_task` → 定时重投（≤3 次）+ 用户手动重试 | **先对账业务状态再重投**，避免重复调 AI |

**实测验证（故障注入，不是纸面设计）**：

| 验证项 | 结果 |
|---|---|
| 注入无效 API Key 后连续失败 | 熔断器 `CLOSED → OPEN`，`failureRate=100%` |
| 熔断打开后的调用 | **1 毫秒内快速失败**，日志「未发起真实调用」，无 401 请求发出 |
| MQ 重试耗尽 | 消息进入 `%DLQ%interview-consumer-group`，落 `failed_task`（`status=0 待重投`） |
| 恢复 Key 之后 | 定时任务自动重投 → 会话出题成功（5 道题）→ `failed_task.status=1` |

> **完整闭环**：LLM 故障 → 熔断 → 快速失败 → 死信 → 落库 → 上游恢复 → 自动重投 → 业务恢复。
> 全程无人工介入。

**两个值得说的设计选择**：

- **熔断包在「单次模型调用」层，而不是整个业务调用外层**：一次业务失败若三次调用全挂会计入
  3 次熔断失败——这是**刻意**的。「连续多次调用都失败」正是依赖不可用的强信号，
  这样能更快熔断；若包在最外层，按 LLM 单次 6~10 秒算要等半分钟以上才熔断，太慢。
- **失败任务唯一键用 `(task_type, biz_id)` 而非消息 ID**：MQ 重试会生成新的消息 ID，
  用消息 ID 去重等于没去重；而同一会话/同一回答在业务上只会成功一次。

**可观测**：`/api/health` 暴露 `llmCircuitBreaker`（state / failureRate / bufferedCalls / failedCalls）。
熔断打开时进程本身完全健康，只看 `UP` 是发现不了的。

---

## 八、项目结构

```
ai-interview-simulator/
├── src/main/java/com/aicoach/     # 后端
│   ├── ai/              # LangChain4j 服务接口 + AgentLoop 编排器 + Function Calling 工具
│   ├── common/          # Result / 异常 / JWT / 限流切面 / 重试 / 缓存 key 构造
│   ├── config/          # MyBatis-Plus / WebMvc / Redis / 响应缓存 / 熔断 / LangChain4j / Knife4j
│   ├── constant/        # 状态与题型枚举（SessionStatus / AnswerStatus / FailedTaskStatus / QuestionType / Difficulty）
│   ├── controller/      # 接口层
│   ├── dto/             # 入参 DTO / 出参 VO
│   ├── entity/          # 数据库实体（含 FailedTask 失败任务）
│   ├── job/             # 定时任务（FailedTaskRetryJob 失败任务重投）
│   ├── mapper/          # MyBatis-Plus Mapper
│   ├── mq/              # RocketMQ 生产者 / 消费者 / 死信消费者 / 消息体
│   └── service/         # 业务层（含 ResponseCacheService 精准失效 / FailedTaskService 兜底）
├── client/                        # 前端（Vue 3 + Vite + Element Plus）
│   ├── src/
│   │   ├── api/         # axios 实例（JWT 注入 / 统一错误处理）+ 接口定义
│   │   ├── router/      # 路由 + 登录守卫
│   │   ├── stores/      # Pinia（登录态持久化）
│   │   ├── layouts/     # 顶部导航布局
│   │   ├── views/       # 5 个页面（登录 / 列表 / 新建 / 答题 / 个人中心）
│   │   └── utils/       # 状态字典 / 时间格式化
│   ├── nginx.conf       # SPA 回退 + /api 反向代理
│   └── Dockerfile       # 多阶段构建：node build → nginx
├── bench/                         # 性能压测（自研零依赖）
│   ├── bench.js         # Node 内置 http 实现的压测器，解析 body.code 统计业务码
│   └── run-bench.sh     # 一键跑完整压测并输出报告
├── docs/                          # PRD 与性能压测报告
├── docker-compose.yml   # 6 个服务（mysql / redis / namesrv / broker / app / web）
└── verify-full.sh       # 全链路回归脚本（63 项断言，含缓存越权/失效与熔断/重试红线）
```

---

## 九、数据库设计

7 张表：`user`（用户）、`interview_session`（会话）、`question`（题目）、
`answer`（回答）、`feedback`（评分反馈）、`ai_call_log`（AI 调用日志 / 幂等）、
`failed_task`（失败任务 / 死信兜底与重投）。

关键索引：
- `interview_session(user_id, created_at DESC)` —— 用户会话列表
- `interview_session(jd_md5)` —— JD 复用查询
- `ai_call_log(user_id, call_md5)` 唯一索引 —— 幂等去重

AgentLoop 相关字段：
- `question.dimension` —— 考察维度（Planner 拆解），用于分组展示与覆盖度校验
- `interview_session.agent_rounds` —— 实际出题轮数（0=复用历史 / 1=首轮通过 / 2=修订过一次）

> 已建库升级：执行 `src/main/resources/sql/migration-20260927-agentloop.sql`（不可重复执行）。

---

## 十、已知限制

- 前端为功能导向的轻量实现，未做单元测试与端到端测试
- 后端未写单元测试（优先保证功能完整度）
- 会话的「岗位名 / 技术栈」字段目前是占位值，待后续用 AI 回填
