package com.aicoach.mq;

import com.aicoach.ai.AgentResult;
import com.aicoach.ai.InterviewAgentLoop;
import com.aicoach.constant.SessionStatus;
import com.aicoach.entity.AiCallLog;
import com.aicoach.entity.InterviewSession;
import com.aicoach.mapper.AiCallLogMapper;
import com.aicoach.mapper.InterviewSessionMapper;
import com.aicoach.service.QuestionService;
import com.aicoach.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 出题任务消费者
 *
 * 异步执行 AI 出题，带：
 * - AgentLoop 三角色闭环（Planner / Executor / Critic），见 {@link InterviewAgentLoop}
 * - 幂等去重（ai_call_log 唯一键）
 *
 * 状态变更一律走 {@link SessionService}：它带事务，并内置「已完成不允许改判为失败」的守卫。
 * 本类自己不加 @Transactional —— 内部私有方法自调用不会走代理，加了也是白加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = InterviewProducer.TOPIC,
        consumerGroup = "interview-consumer-group"
)
public class InterviewConsumer implements RocketMQListener<InterviewMessage> {

    /** ai_call_log.status 取值：1=成功 0=失败（与会话/回答的状态语义无关） */
    private static final int CALL_SUCCESS = 1;

    private final InterviewAgentLoop agentLoop;
    private final InterviewSessionMapper sessionMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final QuestionService questionService;
    private final SessionService sessionService;

    @Override
    public void onMessage(InterviewMessage msg) {
        log.info("收到出题任务: sessionId={}, jdMd5={}", msg.getSessionId(), msg.getJdMd5());

        try {
            // 1. 幂等检查：同一 (userId, jdMd5) 已成功处理过 → 复用已有题目，不要重复调 AI。
            //    两个坑都要避开：
            //    - 不能直接 return，否则当前会话永远停在 status=0，前端轮询不到结果
            //    - 若当前会话已完成（MQ 重投场景），也不能做任何事，否则会把「成功」改判成「失败」
            if (isAlreadyProcessed(msg)) {
                InterviewSession current = sessionMapper.selectById(msg.getSessionId());
                if (current != null && SessionStatus.DONE.matches(current.getStatus())) {
                    log.info("幂等命中且当前会话已完成，跳过: sessionId={}", msg.getSessionId());
                    return;
                }
                reuseFromProcessed(msg);
                return;
            }

            // 2. 跑 AgentLoop 出题：Planner 拆维度 → Executor 出题 → Critic 审核 →
            //    不合格则定向修订（最多两轮）。每次模型调用的重试、降级策略都在 AgentLoop 内部。
            //    这里埋点记录真实耗时：它是「同步模型下用户要等多久」的实测值，
            //    也是量化 MQ 异步化收益的唯一依据（见 docs/性能压测报告.md）。
            long startMs = System.currentTimeMillis();
            AgentResult result = agentLoop.run(msg.getJdContent());
            int costMs = (int) (System.currentTimeMillis() - startMs);

            // 3. 落库题目 + 置完成 + 记录 Agent 轮数（同一事务，避免「题目不全却已完成」）
            int saved = sessionService.saveQuestionsAndMarkDone(
                    msg.getSessionId(), result.questions(), result.rounds());

            // 4. 记录幂等标记（同时落耗时）
            markProcessed(msg, costMs);

            log.info("出题完成: sessionId={}, 题目数={}, agent轮数={}, AI耗时={}ms, 计划={}",
                    msg.getSessionId(), saved, result.rounds(), costMs,
                    InterviewAgentLoop.describePlan(result.plan()));

        } catch (Exception e) {
            log.error("出题失败: sessionId={}", msg.getSessionId(), e);

            InterviewSession session = sessionMapper.selectById(msg.getSessionId());
            // 已是「已完成」说明业务其实成功了，异常发生在成功之后（例如幂等标记撞唯一键）。
            // 此时绝不能改判为失败，否则前端会看到状态从 1 抖到 2；也不需要重试。
            if (session != null && SessionStatus.DONE.matches(session.getStatus())) {
                log.warn("会话已完成，忽略本次异常: sessionId={}", msg.getSessionId());
                return;
            }
            sessionService.updateStatus(msg.getSessionId(), SessionStatus.FAILED);
            // 抛出异常，交给 RocketMQ 重试机制
            throw new RuntimeException("出题任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 命中幂等时：把已有会话的题目复制到当前会话，并把状态置为完成。
     * 不能直接 return —— 否则当前会话永远停在 status=0，前端永远轮询不到结果。
     */
    private void reuseFromProcessed(InterviewMessage msg) {
        // 防重：当前会话已有题目说明之前复制过（消息重投），只补状态不再复制，否则题目会翻倍。
        // 因为「复制 + 置完成」是原子的（见 copyQuestionsAndMarkDone），
        // 有题目就等价于复制完整了。
        if (questionService.countBySession(msg.getSessionId()) > 0) {
            sessionService.updateStatus(msg.getSessionId(), SessionStatus.DONE);
            log.info("幂等复用：当前会话已有题目，仅补状态: sessionId={}", msg.getSessionId());
            return;
        }

        InterviewSession existing = sessionMapper.selectOne(
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getUserId, msg.getUserId())
                        .eq(InterviewSession::getJdMd5, msg.getJdMd5())
                        .eq(InterviewSession::getStatus, SessionStatus.DONE.getCode())
                        .ne(InterviewSession::getId, msg.getSessionId())
                        .orderByDesc(InterviewSession::getId)
                        .last("LIMIT 1"));

        if (existing == null) {
            log.warn("命中幂等但找不到可复用的会话: sessionId={}", msg.getSessionId());
            sessionService.updateStatus(msg.getSessionId(), SessionStatus.FAILED);
            return;
        }

        // 源会话 0 道题时不能置「完成」，否则前端拿到空题目的成功态
        if (questionService.countBySession(existing.getId()) == 0) {
            log.warn("可复用会话无题目: sessionId={}, 源 sessionId={}",
                    msg.getSessionId(), existing.getId());
            sessionService.updateStatus(msg.getSessionId(), SessionStatus.FAILED);
            return;
        }

        // 复制题目 + 置为完成在同一事务里：中途失败整体回滚，
        // 不会留下「题目不全却已完成」的会话
        int copied = sessionService.copyQuestionsAndMarkDone(existing.getId(), msg.getSessionId());
        log.info("幂等复用完成: sessionId={}, 复用 {} 道题", msg.getSessionId(), copied);
    }

    private boolean isAlreadyProcessed(InterviewMessage msg) {
        Long count = aiCallLogMapper.selectCount(
                new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getUserId, msg.getUserId())
                        .eq(AiCallLog::getCallMd5, msg.getJdMd5())
                        .eq(AiCallLog::getStatus, CALL_SUCCESS));
        return count != null && count > 0;
    }

    private void markProcessed(InterviewMessage msg, int durationMs) {
        AiCallLog logEntity = new AiCallLog();
        logEntity.setUserId(msg.getUserId());
        logEntity.setCallMd5(msg.getJdMd5());
        logEntity.setToolName("agentLoop.generateQuestions");
        logEntity.setDurationMs(durationMs);
        logEntity.setStatus(CALL_SUCCESS);
        try {
            aiCallLogMapper.insert(logEntity);
        } catch (DuplicateKeyException e) {
            // uk_user_md5(user_id, call_md5) 已存在 = 幂等标记早就写好了
            // （同一 JD 被并发创建、或 MQ 重投时会出现）。
            // 这里必须吞掉异常：若抛到外层 catch，会把「已出题成功」的会话改判成「失败」。
            log.info("幂等标记已存在，忽略: userId={}, jdMd5={}", msg.getUserId(), msg.getJdMd5());
        }
    }
}
