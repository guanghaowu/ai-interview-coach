-- ============================================================
-- 迁移：ai_call_log 增加 AI 调用耗时字段
-- 目的：量化「MQ 异步化」的收益 —— 有了真实耗时，
--       才能算出「同步模型下这个接口要等多久」。
-- 执行：不可重复执行（重复执行会报 Duplicate column）
--   docker exec -i ai-coach-mysql mysql -uroot -p123456 ai_coach < src/main/resources/sql/migration-20260927-duration.sql
-- ============================================================

ALTER TABLE `ai_call_log`
    ADD COLUMN `duration_ms` INT DEFAULT NULL COMMENT 'AI 调用真实耗时(ms)，用于量化异步化收益'
    AFTER `total_tokens`;
