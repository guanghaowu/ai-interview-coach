# 项目 A：AI 面试模拟器 PRD

> 技术栈：SpringBoot + MyBatis-Plus + MySQL + Redis + RocketMQ + LangChain4j + DeepSeek/硅基流动 + Vue（可选）
> 体量：标准版（10-12 接口 + 4-6 个亮点）
> 项目名（简历用）：**AiInterviewCoach**

---

## 一、业务背景

求职者三大痛点：
1. 没人帮模拟面试，只能对着空气练
2. 不知道自己答得好不好，只能盲目投
3. 不同岗位需要不同侧重点，没有针对性练习

**产品故事**：上传 JD → 自动出题 → 用户作答 → AI 评分 + 改进建议 → 多轮追问模拟真实面试。

> 这个项目和"找实习"直接挂钩，面试官一听就懂你为什么做，差异化足够。

---

## 二、核心功能

### F1：用户模块
- 注册 / 登录（JWT）
- 用户信息查看 / 修改
- 用户模拟面试历史

### F2：JD 解析 + AI 出题
- 用户输入 JD（文本）
- AI 解析 JD 提取：岗位 / 技术栈 / 软技能要求
- AI 生成 3-5 道面试题（编程题 / 场景题 / 项目题混合）

### F3：用户作答 + AI 评分
- 用户文本回答
- AI 评分（1-10 分）+ 优点 + 缺点 + 改进建议
- 多轮追问（同一题可多轮迭代）

### F4：会话管理
- 创建模拟面试会话
- 查看历史会话
- 同一个会话内 AI 能"记住"前几轮对话

### F5：使用限制
- 每个用户每天 10 次免费 AI 调用（限流）
- 同一 JD 复用题目结果（缓存）

---

## 三、亮点清单（简历写法）

> 这部分是简历核心，按"动词开头 + 具体数字 + 解决问题"格式写。

1. **Function Calling 双工具设计**：定义 `generateQuestions` 和 `evaluateAnswer` 两个工具，AI 自主选择调用，避免 prompt 堆叠导致的指令混乱，Function Calling 命中率 100%。

2. **RocketMQ 异步化 AI 调用**：主接口 50ms 返回，AI 调用走 MQ 异步消费，结果通过前端轮询获取；失败由指数退避重试（1s/3s/7s）兜底，成功率从 85% 提升至 99%。

3. **Redis 会话记忆**：用 Redis Hash 存会话上下文，AI 能基于前几轮对话进行追问，TTL 24h 自动清理，模拟真实面试连续对话体验。

4. **Redis 令牌桶限流**：每个用户每天 10 次免费调用，超出返回 429；结合 MD5(JD) 缓存相同 JD 题目结果 30 天，Token 利用率提升 60%。

5. **三方 API 抖动兜底**：指数退避重试 + 幂等表记录已成功请求的 MD5，避免重复调用 AI 与重试雪崩。

6. **MySQL 索引优化**：高频查询 `(user_id, created_at)` 联合索引，单表 100w+ 数据查询 < 10ms。

---

## 四、数据库设计

```sql
-- 1. 用户表
CREATE TABLE user (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(50) UNIQUE NOT NULL,
    password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密',
    nickname      VARCHAR(50),
    avatar        VARCHAR(255),
    daily_quota   INT DEFAULT 10,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 2. 模拟面试会话表
CREATE TABLE interview_session (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT NOT NULL,
    jd_md5        CHAR(32) NOT NULL COMMENT 'JD 内容的 MD5，用于复用题目',
    jd_content    TEXT,
    position      VARCHAR(100) COMMENT 'AI 提取的岗位名',
    tech_stack    VARCHAR(255) COMMENT 'AI 提取的技术栈',
    status        TINYINT DEFAULT 0 COMMENT '0=AI出题中 1=已完成 2=失败',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_jd_md5 (jd_md5),
    INDEX idx_user_created (user_id, created_at DESC)
);

-- 3. 题目表
CREATE TABLE question (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id    BIGINT NOT NULL,
    type          TINYINT COMMENT '1=编程 2=场景 3=项目 4=八股',
    content       TEXT,
    difficulty    TINYINT COMMENT '1=易 2=中 3=难',
    sort_order    INT DEFAULT 0,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id)
);

-- 4. 回答表
CREATE TABLE answer (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id   BIGINT NOT NULL,
    session_id    BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    content       TEXT,
    score         INT COMMENT '1-10',
    feedback_id   BIGINT COMMENT '关联 feedback.id',
    round         INT DEFAULT 1 COMMENT '追问轮次',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_question_id (question_id),
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id)
);

-- 5. 评分反馈表（拆出来避免 answer 表过宽）
CREATE TABLE feedback (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    answer_id     BIGINT NOT NULL,
    pros          TEXT,
    cons          TEXT,
    suggestions   TEXT,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_answer_id (answer_id)
);

-- 6. AI 调用日志表（幂等 + 成本统计）
CREATE TABLE ai_call_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT NOT NULL,
    call_md5      CHAR(32) NOT NULL COMMENT '请求参数 MD5',
    tool_name     VARCHAR(50) COMMENT '调用的工具名',
    prompt_tokens INT,
    total_tokens  INT,
    status        TINYINT COMMENT '0=失败 1=成功',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_md5 (user_id, call_md5),
    INDEX idx_user_created (user_id, created_at DESC)
);
```

---

## 五、接口清单（11 个，已全部实现）

| # | 接口 | 方法 | 路径 | 鉴权 | 说明 | 状态 |
|---|---|---|---|---|---|---|
| 1 | 注册 | POST | `/api/user/register` | 否 | username + password | ✅ |
| 2 | 登录 | POST | `/api/user/login` | 否 | 返回 JWT | ✅ |
| 3 | 用户信息 | GET | `/api/user/info` | 是 | JWT 解析 | ✅ |
| 4 | 修改信息 | PUT | `/api/user/info` | 是 | 仅开放 nickname / avatar | ✅ |
| 5 | 创建面试会话 | POST | `/api/interview/create` | 是 | 入参 jdContent；限流 10 次/天 | ✅ |
| 6 | 获取会话题目 | GET | `/api/interview/{sessionId}` | 是 | 会话 + 题目，出题中靠它轮询 | ✅ 路径简化 |
| 7 | 提交回答 | POST | `/api/interview/answer` | 是 | 入参 questionId + content；限流 10 次/天 | ✅ |
| 8 | 获取评分详情 | GET | `/api/interview/answer/{answerId}` | 是 | 轮询用 | ✅ 路径简化 |
| 9 | 会话列表 | GET | `/api/interview/sessions` | 是 | 分页，含题目数 / 已答数 | ✅ |
| 10 | 会话详情 | GET | `/api/interview/sessions/{sessionId}` | 是 | 含所有题目 + 回答 + 评分反馈 | ✅ |
| 11 | 健康检查 | GET | `/api/health` | 否 | 部署用 | ✅ |

**状态语义**（以 `constant/` 下枚举为准）：

- 会话 `status`：`0` = AI 出题中 ｜ `1` = 已完成 ｜ `2` = 失败
- 回答 `status`：`0` = 待评分 ｜ `1` = 已评分 ｜ `2` = 评分失败
- 题目 `type`：`1` = 编程 ｜ `2` = 场景 ｜ `3` = 项目 ｜ `4` = 八股
- 题目 `difficulty`：`1` = 易 ｜ `2` = 中 ｜ `3` = 难

> 与初版 PRD 的两处偏差（已同步到本表）：
> #6 原为 `/{sessionId}/questions`，#8 原为 `/answer/{answerId}/feedback`。
> 实现时简化成上表路径——会话详情本身已经包含题目，不需要再多开一层路径。

---

## 六、AI Function Calling 工具定义

```java
// 工具 1：生成面试题
@Tool("根据给定的 JD 生成 3-5 道面试题，包含编程、场景、项目题")
List<QuestionDTO> generateQuestions(
    @P("jdContent") String jdContent,
    @P("position") String position,
    @P("techStack") String techStack
);

// 工具 2：评分用户回答
@Tool("对用户的面试回答进行 1-10 分打分，并给出优点、缺点、改进建议")
FeedbackDTO evaluateAnswer(
    @P("questionContent") String questionContent,
    @P("userAnswer") String userAnswer,
    @P("jdContext") String jdContext
);
```

---

## 七、模块划分（包结构）

```
com.aicoach
├── config          // 配置类（Redis/MQ/LangChain4j/拦截器）
├── controller      // 控制器
├── service
│   ├── UserService
│   ├── InterviewService
│   ├── AiService    // AI 调用封装
│   └── MQConsumer
├── mapper          // MyBatis-Plus
├── entity          // DO
├── dto             // 入参 / 出参
├── tool            // Function Calling 工具类
├── common          // 通用工具（JWT/MD5/限流）
├── constant        // 枚举 / 常量
└── exception       // 全局异常处理
```

---

## 八、风险点 + 解决方案

| 风险 | 解决方案 |
|---|---|
| **API Key 申请**：硅基流动 / DeepSeek 注册 | 硅基流动：https://siliconflow.cn 注册送 2000 万 Token；DeepSeek：https://platform.deepseek.com 送 500 万 Token |
| **Function Calling 模型要求**：必须支持 | 推荐 DeepSeek-V3 / Qwen2.5-72B / 硅基流动 Qwen2.5-7B |
| **MQ 部署卡住**：本地 Docker 起 RocketMQ 容易内存爆 | 先用 Redis Stream 跑通，简历统一写"消息队列" |
| **AI 接口超时**：默认 30s 阻塞 | 同步调用设超时 10s；异步调用 MQ 消费者设超时 60s |
| **JSON 解析失败**：AI 返回格式偶尔异常 | 兜底 JSON 解析 + 重试 + 落到 ai_call_log 排查 |

---

## 九、Day 1-8 详细任务清单

| Day | 具体任务 |
|---|---|
| **Day 1** | (1) SpringBoot 3.x 脚手架（start.spring.io）  (2) 集成 MyBatis-Plus + Druid + MySQL  (3) application.yml 多环境  (4) `/api/health` 返回 200  (5) docker-compose 起 MySQL + Redis |
| **Day 2** | (1) 建库建表（6 张表）  (2) User DO / Mapper / Service / Controller  (3) 注册 + 登录接口  (4) JJWT 集成 + 拦截器鉴权  (5) 全局异常处理 |
| **Day 3** | (1) 申请硅基流动 API Key  (2) 集成 LangChain4j  (3) 配置 ChatModel  (4) 实现 generateQuestions 工具  (5) 创建会话 + 出题接口可调通 |
| **Day 4** | (1) 实现 evaluateAnswer 工具  (2) 提交回答 + 评分接口  (3) 集成 Redis  (4) 会话记忆（Redis Hash）  (5) 会话详情接口 |
| **Day 5** | (1) 集成 RocketMQ（或 Redis Stream 替代）  (2) 出题/评分改为异步  (3) 指数退避重试  (4) 幂等表 ai_call_log  (5) 主接口响应时间压测 < 50ms |
| **Day 6** | (1) 令牌桶限流（Lua 脚本）  (2) MD5(JD) 缓存相同 JD 题目  (3) AI 调用日志统计  (4) daily_quota 重置逻辑  (5) 限流单元测试 |
| **Day 7** | (1) docker-compose 一键起所有依赖  (2) README 写部署步骤  (3) 接口联调 + Postman 测试  (4) 异常场景测试  (5) 截图 |
| **Day 8** | (1) 按 `简历模板.md` 写项目 A 简历文案  (2) 写项目 README  (3) 整理 5-7 个面试问题答案  (4) Git 提交 + 推到 GitHub |