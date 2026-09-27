# AiInterviewCoach —— AI 面试模拟平台

> 输入岗位 JD → AI 自动生成面试题 → 作答 → AI 评分并给出改进建议，完整模拟一场技术面试。

![Java](https://img.shields.io/badge/Java-17-blue)
![SpringBoot](https://img.shields.io/badge/SpringBoot-3.2.5-green)
![MySQL](https://img.shields.io/badge/MySQL-8.0-orange)
![Redis](https://img.shields.io/badge/Redis-7-red)
![RocketMQ](https://img.shields.io/badge/RocketMQ-5.3.1-purple)

---

## 一、项目简介

求职者练习面试时普遍面临三个问题：**没人出题、不知道答得好不好、题目和岗位不匹配**。

AiInterviewCoach 用大模型解决这三点：粘贴一段 JD，系统自动解析岗位要求、生成 3-5 道针对性面试题；
用户作答后，AI 从准确性、深度、完整性三个维度评分，并给出具体的优点、缺点和改进建议。

**核心挑战不在业务逻辑，而在于**：AI 接口单次调用耗时 10-30 秒，同步调用会直接打满 Tomcat 线程池。
因此项目围绕「**异步化 + 成本控制 + 稳定性**」做架构设计。

---

## 二、技术栈

| 分类 | 技术 |
|---|---|
| 框架 | SpringBoot 3.2.5、Spring AOP |
| 持久层 | MyBatis-Plus 3.5.9、Druid 连接池、MySQL 8.0 |
| 缓存 / 限流 | Redis 7（会话记忆、令牌桶限流、幂等） |
| 消息队列 | RocketMQ 5.3.1（AI 调用异步化、失败重试） |
| 大模型 | LangChain4j 0.36.2 + DeepSeek（OpenAI 兼容协议） |
| 安全 | JWT（JJWT 0.12.5）+ BCrypt 密码加密 |
| 接口文档 | Knife4j 4.5.0（OpenAPI3 / springdoc 2.3.0） |
| 部署 | Docker 多阶段构建 + Docker Compose |
| 工具 | Hutool、Lombok |

---

## 三、系统架构

```
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

---

## 四、核心功能

| 功能 | 说明 |
|---|---|
| 用户体系 | 注册 / 登录 / JWT 鉴权 / BCrypt 密码加密 |
| AI 出题 | 提交 JD → 生成 3-5 道题（题型混合：八股 / 场景 / 编程 / 项目） |
| AI 评分 | 提交回答 → 1-10 分 + 优点 / 缺点 / 改进建议 |
| 会话记忆 | Redis 存对话历史（TTL 24h，保留最近 20 条） |
| 使用配额 | 每用户每天 10 次 AI 调用（Redis 令牌桶） |
| 结果复用 | **同一用户**提交相同 JD 时复用其历史题目，节省 Token（按 userId 隔离，不会串到他人） |

---

## 五、接口清单

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/user/register` | 否 | 注册，返回 JWT |
| POST | `/api/user/login` | 否 | 登录，返回 JWT |
| GET | `/api/user/info` | 是 | 当前用户信息 |
| POST | `/api/interview/create` | 是 | 创建会话（AI 异步出题，限流 10 次/天） |
| GET | `/api/interview/{sessionId}` | 是 | 会话详情（含题目），轮询出题结果 |
| POST | `/api/interview/answer` | 是 | 提交回答（AI 异步评分，限流 10 次/天） |
| GET | `/api/interview/answer/{answerId}` | 是 | 轮询评分结果 |
| GET | `/api/health` | 否 | 健康检查 |

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

# 2. 启动全部服务（MySQL + Redis + RocketMQ + 应用）
docker compose up -d

# 3. 建库建表（首次）
docker exec -i ai-coach-mysql mysql -uroot -p123456 < src/main/resources/sql/init.sql

# 4. 验证
curl http://localhost:8080/api/health
```

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

---

## 七、技术亮点

### 1. RocketMQ 异步化，主接口秒级返回
AI 出题耗时 10-30 秒。同步调用会占满 Tomcat 线程池，并发稍高就阻塞整个服务。
改为「建会话 + 投 MQ」后主接口立即返回，AI 调用在消费端执行，前端轮询取结果。

### 2. 指数退避重试 + 幂等去重
三方 API 存在网络抖动。失败后按 **1s → 2s → 4s** 退避重试（最多 3 次）；
同时用 `ai_call_log` 表以 `(userId, jdMd5)` 为唯一键做幂等（**按用户维度**，不会串到他人），
避免 MQ 重投导致重复出题。

> 踩坑记录：幂等标记的 insert 必须捕获唯一键冲突。否则并发/重投时异常会冒到外层 catch，
> 把「已经出题成功」的会话无条件改判成「失败」，前端能看到状态从 1 抖到 2。
> 同理，失败兜底逻辑要先判断当前是否已是完成态，不能无脑覆盖。

### 3. Redis 令牌桶限流（Lua 原子执行）
每用户每天 10 次 AI 调用配额，用 Lua 脚本保证「取令牌 + 回写」的原子性。

> 踩坑记录：速率计算必须用 `double`。若用 `long`，`10/86400` 取整为 `0`、被兜底成 `1`，
> 会导致补充速率大于消耗速率，**限流彻底失效**。

### 4. Function Calling 获取真实上下文
通过 LangChain4j `@Tool` 暴露两个工具（查询会话题目、查询题目原文），
让模型评分时能主动调用工具获取上下文，而不是凭空编造。

### 5. Redis 会话记忆
用 Redis List 存对话历史（`interview:memory:{sessionId}`），TTL 24h，自动 trim 到最近 20 条，
支持多轮追问场景。

### 6. Docker 多阶段构建
构建阶段用 Maven 镜像编译，运行阶段只保留 JRE（alpine），显著减小镜像体积；
`COPY pom.xml` 单独下载依赖以利用 Docker 层缓存。

### 7. 事务边界设计
- `createSession` 用 `@Transactional` 把「会话落库 + MQ 投递」绑成一个原子操作，投递失败即回滚，
  不会留下永远停在 `status=0` 的僵尸会话；
- 评分落库则相反：AI 调用耗时 5-15 秒，**绝不能放进事务**，否则一个请求就要占着数据库连接 15 秒，
  连接池（max-active=20）并发 20 就爆。所以单独抽出 `FeedbackService.saveGradingResult`，
  事务只包住「插 feedback + 回填 answer」两条写操作，外部调用留在事务外。

### 8. Knife4j 在线接口文档
集成 OpenAPI3（springdoc），`/doc.html` 可视化调试，全局配置 JWT 认证方案，
点一下 Authorize 就能带 token 调接口，省去手写 curl。

---

## 八、项目结构

```
src/main/java/com/aicoach/
├── ai/              # LangChain4j 服务接口 + Function Calling 工具
├── common/          # Result / 异常 / JWT / 限流切面 / 重试工具
├── config/          # MyBatis-Plus / WebMvc / Redis / LangChain4j / Knife4j 配置
├── constant/        # 状态与题型枚举（SessionStatus / AnswerStatus / QuestionType / Difficulty）
├── controller/      # 接口层
├── dto/             # 入参 DTO / 出参 VO
├── entity/          # 数据库实体
├── mapper/          # MyBatis-Plus Mapper
├── mq/              # RocketMQ 生产者 / 消费者 / 消息体
└── service/         # 业务层
```

---

## 九、数据库设计

6 张表：`user`（用户）、`interview_session`（会话）、`question`（题目）、
`answer`（回答）、`feedback`（评分反馈）、`ai_call_log`（AI 调用日志 / 幂等）。

关键索引：
- `interview_session(user_id, created_at DESC)` —— 用户会话列表
- `interview_session(jd_md5)` —— JD 复用查询
- `ai_call_log(user_id, call_md5)` 唯一索引 —— 幂等去重

---

## 十、已知限制

- 前端未实现（当前通过 curl / Postman 验证）
- 未写单元测试（优先保证功能完整度）
- 会话的「岗位名 / 技术栈」字段目前是占位值，待后续用 AI 回填
