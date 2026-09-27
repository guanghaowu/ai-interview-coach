-- =====================================================
-- 迁移：新增 failed_task 表（死信兜底 + 定时重投）
-- 日期：2026-09-27
-- 背景：MQ 消费失败 → 重试耗尽 → 进死信队列后此前无人处理，
--       用户侧永久卡在「失败」态。本表用于承接死信消息并驱动重投。
-- 执行：docker exec -i ai-coach-mysql mysql -uroot -p123456 -D ai_coach < migration-20260927-failed-task.sql
-- 注意：可重复执行（IF NOT EXISTS）
-- =====================================================

CREATE TABLE IF NOT EXISTS `failed_task` (
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
    UNIQUE KEY `uk_type_biz` (`task_type`, `biz_id`),
    KEY `idx_status_retry` (`status`, `retry_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='失败任务（死信兜底 + 重投）';
