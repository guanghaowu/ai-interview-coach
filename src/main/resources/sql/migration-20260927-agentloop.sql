-- =====================================================
-- AgentLoop 上线迁移脚本（2026-09-27）
--
-- 适用场景：数据库已经用旧版 init.sql 建过表，需要补两列。
-- 全新库不需要执行本脚本，直接跑 init.sql 即可。
--
-- 注意：本脚本不可重复执行（第二次会报 Duplicate column）。
-- =====================================================

-- question 增加「考察维度」：Planner 拆出的维度名，
-- 用于前端按维度分组展示，以及 Critic 校验覆盖度。
ALTER TABLE `question`
    ADD COLUMN `dimension` VARCHAR(50) DEFAULT NULL
        COMMENT '考察维度（Planner 拆解），用于分组展示与覆盖度校验'
        AFTER `difficulty`;

-- interview_session 增加「Agent 执行轮数」：
-- 0=未跑 AgentLoop（复用历史题目）/ 1=首轮即通过 / 2=触发过一次定向修订。
-- 让 AgentLoop 的执行过程可观测，而不只是日志里能翻到。
ALTER TABLE `interview_session`
    ADD COLUMN `agent_rounds` TINYINT NOT NULL DEFAULT 0
        COMMENT 'AgentLoop 出题轮数：0=未跑/复用历史 1=首轮通过 2=修订过一次'
        AFTER `status`;
