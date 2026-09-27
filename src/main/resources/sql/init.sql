-- =====================================================
-- AI 面试模拟平台 数据库初始化脚本
-- Day 2 建库建表
-- =====================================================

CREATE DATABASE IF NOT EXISTS ai_coach DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_coach;

-- -----------------------------------------------------
-- 1. 用户表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名（登录用，唯一）',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
    `nickname`    VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    `avatar`      VARCHAR(255)          DEFAULT NULL COMMENT '头像 URL',
    `daily_quota` INT          NOT NULL DEFAULT 10 COMMENT '每日 AI 调用配额',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=未删 1=已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- -----------------------------------------------------
-- 2. 模拟面试会话表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `interview_session`;
CREATE TABLE `interview_session` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT      NOT NULL COMMENT '用户 ID',
    `jd_md5`      CHAR(32)    NOT NULL COMMENT 'JD 内容的 MD5，用于复用题目',
    `jd_content`  TEXT                 DEFAULT NULL COMMENT '原始 JD',
    `position`    VARCHAR(100)         DEFAULT NULL COMMENT 'AI 提取的岗位名',
    `tech_stack`  VARCHAR(255)         DEFAULT NULL COMMENT 'AI 提取的技术栈',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=AI出题中 1=已完成 2=失败',
    `agent_rounds` TINYINT    NOT NULL DEFAULT 0 COMMENT 'AgentLoop 出题轮数：0=未跑/复用历史 1=首轮通过 2=修订过一次',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_jd_md5` (`jd_md5`),
    KEY `idx_user_created` (`user_id`, `created_at` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟面试会话';

-- -----------------------------------------------------
-- 3. 题目表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `question`;
CREATE TABLE `question` (
    `id`          BIGINT  NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT  NOT NULL COMMENT '所属会话 ID',
    `type`        TINYINT          DEFAULT NULL COMMENT '1=编程 2=场景 3=项目 4=八股',
    `content`     TEXT             DEFAULT NULL COMMENT '题目内容',
    `difficulty`  TINYINT          DEFAULT NULL COMMENT '1=易 2=中 3=难',
    `dimension`   VARCHAR(50)      DEFAULT NULL COMMENT '考察维度（Planner 拆解），用于分组展示与覆盖度校验',
    `sort_order`  INT     NOT NULL DEFAULT 0 COMMENT '题目序号',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted`     TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='面试题';

-- -----------------------------------------------------
-- 4. 回答表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `answer`;
CREATE TABLE `answer` (
    `id`           BIGINT  NOT NULL AUTO_INCREMENT,
    `question_id`  BIGINT  NOT NULL,
    `session_id`   BIGINT  NOT NULL,
    `user_id`      BIGINT  NOT NULL,
    `content`      TEXT             DEFAULT NULL,
    `score`        INT              DEFAULT NULL COMMENT 'AI 评分 1-10',
    `feedback_id`  BIGINT           DEFAULT NULL COMMENT '关联 feedback.id',
    `round`        INT     NOT NULL DEFAULT 1 COMMENT '追问轮次',
    `status`       TINYINT NOT NULL DEFAULT 0 COMMENT '0=待评分 1=已评分 2=失败',
    `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted`      TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_question_id` (`question_id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户回答';

-- -----------------------------------------------------
-- 5. 评分反馈表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `feedback`;
CREATE TABLE `feedback` (
    `id`           BIGINT  NOT NULL AUTO_INCREMENT,
    `answer_id`    BIGINT  NOT NULL,
    `pros`         TEXT             DEFAULT NULL,
    `cons`         TEXT             DEFAULT NULL,
    `suggestions`  TEXT             DEFAULT NULL,
    `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_answer_id` (`answer_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AI 评分反馈';

-- -----------------------------------------------------
-- 6. AI 调用日志表（幂等 + 成本统计）
-- -----------------------------------------------------
DROP TABLE IF EXISTS `ai_call_log`;
CREATE TABLE `ai_call_log` (
    `id`             BIGINT  NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT  NOT NULL,
    `call_md5`       CHAR(32) NOT NULL COMMENT '请求参数 MD5',
    `tool_name`      VARCHAR(50)  DEFAULT NULL,
    `prompt_tokens`  INT          DEFAULT NULL,
    `total_tokens`   INT          DEFAULT NULL,
    `duration_ms`    INT          DEFAULT NULL COMMENT 'AI 调用真实耗时(ms)，用于量化异步化收益',
    `status`         TINYINT NOT NULL DEFAULT 0 COMMENT '0=失败 1=成功',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_md5` (`user_id`, `call_md5`),
    KEY `idx_user_created` (`user_id`, `created_at` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AI 调用日志';

-- -----------------------------------------------------
-- 7. 失败任务表（死信兜底 + 定时重投）
-- -----------------------------------------------------
DROP TABLE IF EXISTS `failed_task`;
CREATE TABLE `failed_task` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `task_type`   VARCHAR(20)  NOT NULL COMMENT 'INTERVIEW=出题 ANSWER=评分',
    `topic`       VARCHAR(100) NOT NULL COMMENT '原始 topic，重投时原样发回',
    `biz_id`      BIGINT NOT NULL COMMENT '业务主键：出题=session_id，评分=answer_id',
    `user_id`     BIGINT       DEFAULT NULL,
    `payload`     TEXT         NOT NULL COMMENT '原始消息体 JSON，重投时反序列化后发回',
    `error_msg`   VARCHAR(500) DEFAULT NULL COMMENT '最后一次失败原因（截断存储）',
    `retry_count` INT     NOT NULL DEFAULT 0 COMMENT '已被定时任务重投的次数',
    `status`      TINYINT NOT NULL DEFAULT 0 COMMENT '0=待重投 1=重投成功 2=已放弃',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 用「业务维度」而非消息 ID 做唯一键：MQ 重试会生成新消息 ID，
    -- 用消息 ID 去重等于没去重；而同一会话/同一回答在业务上只会成功一次。
    UNIQUE KEY `uk_type_biz` (`task_type`, `biz_id`),
    KEY `idx_status_retry` (`status`, `retry_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='失败任务（死信兜底 + 重投）';