# AiInterviewCoach —— AI 面试模拟平台

> SpringBoot 3 + MyBatis-Plus + MySQL + Redis + RocketMQ + LangChain4j + DeepSeek
> 标准版后端项目，用于 Java 后端实习简历

---

## ⚡ Day 1 启动步骤（5 步跑通）

### 1. 启动 MySQL + Redis
```bash
cd ai-interview-simulator
docker-compose up -d mysql redis
docker-compose ps  # 确认两个服务都是 healthy
```

### 2. 验证数据库连接
```bash
docker exec -it ai-coach-mysql mysql -uroot -p123456 -e "SHOW DATABASES;"
# 应该看到 ai_coach 库
```

### 3. 在 IDE 中打开项目
- IDEA: File → Open → 选择 `ai-interview-simulator/pom.xml`
- 等待 Maven 同步依赖（第一次约 3-5 分钟）

### 4. 启动 SpringBoot 应用
- 找到 `AiInterviewCoachApplication.java`
- 右键 → Run
- 控制台看到 `AI Interview Coach 启动成功` 即成功

### 5. 测试健康检查
```bash
curl http://localhost:8080/api/health
```
**预期返回**：
```json
{
  "status": "UP",
  "service": "ai-interview-coach",
  "version": "0.0.1-SNAPSHOT",
  "timestamp": "2026-09-26T19:00:00",
  "day": "Day 1 - 脚手架已完成"
}
```

> ✅ **Day 1 完成标志**：能看到上面这段 JSON

---

## 📂 项目结构

```
ai-interview-simulator/
├── pom.xml                         # Maven 依赖
├── docker-compose.yml              # MySQL + Redis 一键起
├── .gitignore
├── README.md
└── src/main/
    ├── java/com/aicoach/
    │   ├── AiInterviewCoachApplication.java   # 启动类
    │   ├── config/
    │   │   └── MybatisPlusConfig.java         # MyBatis-Plus 配置（分页 + 自动填充）
    │   ├── controller/
    │   │   └── HealthController.java          # 健康检查
    │   └── entity/
    │       └── User.java                      # 用户实体（Day 2 用）
    └── resources/
        ├── application.yml                    # 主配置（多环境）
        └── application-dev.yml                # 开发环境（MySQL/Redis/DeepSeek）
```

---

## 🛠️ 技术栈一览（按简历顺序）

| 组件 | 版本 | 用途 |
|---|---|---|
| SpringBoot | 3.2.5 | 主框架 |
| MyBatis-Plus | 3.5.9 | ORM |
| MySQL | 8.0 | 数据库 |
| Druid | 1.2.23 | 连接池 |
| Redis | 7 | 缓存 / 限流 / 会话记忆 |
| RocketMQ | 2.3.1 | 异步消息（Day 5 接入） |
| LangChain4j | 0.36.2 | 大模型应用框架 |
| DeepSeek | deepseek-chat | 大模型（OpenAI 兼容） |
| JJWT | 0.12.5 | JWT 鉴权（Day 2 用） |
| Hutool | 5.8.27 | 工具集 |
| Java | 17 | 语言 |

---

## 🔌 API 接口清单

### 用户模块（Day 2）
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/user/register` | 否 | 注册，返回 JWT |
| POST | `/api/user/login` | 否 | 登录，返回 JWT |
| GET | `/api/user/info` | 是 | 当前用户信息 |

### 模拟面试模块（Day 3-4）
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/interview/create` | 是 | 创建会话，AI 根据 JD 出题 |
| GET | `/api/interview/{sessionId}` | 是 | 会话详情（含题目列表） |
| POST | `/api/interview/answer` | 是 | 提交回答，AI 评分 + 反馈 |

### 系统
| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/health` | 否 | 健康检查 |

## 🗺️ 接下来的路线（参考作战地图）

| Day | 任务 |
|---|---|
| Day 2 | 建库建表 + User 模块 + JWT 鉴权 |
| Day 3 | LangChain4j 接入 DeepSeek + AI 出题 |
| Day 4 | AI 评分 + Redis 会话记忆 |
| Day 5 | RocketMQ 异步化 + 指数退避重试 |
| Day 6 | Redis 令牌桶限流 + MD5 缓存 |
| Day 7 | Docker 部署 + 接口联调 |
| Day 8 | 简历包装 + README |

---

## ⚠️ 常见问题

### Q1: 启动报 "Failed to bind on 0.0.0.0:8080"
**A**: 端口被占用。修改 `application.yml` 的 `server.port: 8081`

### Q2: MySQL 连接失败 "Communications link failure"
**A**: 检查 `docker-compose ps` 看 mysql 容器是否 healthy。等 30 秒再试。

### Q3: Redis 连接失败
**A**: 检查 `docker-compose ps` 看 redis 容器是否 healthy。

### Q4: 启动后立刻看到 LangChain4j 报错
**A**: 正常，Day 1 没接 DeepSeek 不会用 AI，可以忽略 LangChain4j 启动报错。
   解决：在 `application-dev.yml` 把 `langchain4j` 整段注释掉，或填一个假 API Key。

### Q5: IDEA 报 "Cannot resolve symbol SpringBootApplication"
**A**: Maven 没同步。右侧 Maven 面板 → Reload All Maven Projects。