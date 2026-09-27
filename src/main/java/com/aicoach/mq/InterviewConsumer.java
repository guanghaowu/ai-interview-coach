package com.aicoach.mq;

import com.aicoach.ai.InterviewAiService;
import com.aicoach.common.RetryUtil;
import com.aicoach.constant.SessionStatus;
import com.aicoach.dto.QuestionListDTO;
import com.aicoach.entity.AiCallLog;
import com.aicoach.entity.InterviewSession;
import com.aicoach.mapper.AiCallLogMapper;
import com.aicoach.mapper.InterviewSessionMapper;
import com.aicoach.service.QuestionService;
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
 * - 指数退避重试（应对三方 API 抖动）
 * - 幂等去重（ai_call_log 唯一键）
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

    private final InterviewAiService interviewAiService;
    private final InterviewSessionMapper sessionMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final QuestionService questionService;

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

            // 2. 调 AI 出题（指数退避重试：1s / 2s / 4s）
            QuestionListDTO result = RetryUtil.retryWithBackoff(
                    "AI出题", 3, 1000,
                    () -> interviewAiService.generateQuestions(msg.getJdContent()));

            if (result == null || result.getQuestions() == null || result.getQuestions().isEmpty()) {
                throw new IllegalStateException("AI 返回题目为空");
            }

            // 3. 落库题目（QuestionService 内部会归一化题型/难度，并统一编号）
            int saved = questionService.saveGenerated(msg.getSessionId(), result.getQuestions());

            // 4. 更新会话状态为「已完成」
            updateStatus(msg.getSessionId(), SessionStatus.DONE);

            // 5. 记录幂等标记
            markProcessed(msg);

            log.info("出题完成: sessionId={}, 题目数={}", msg.getSessionId(), saved);

        } catch (Exception e) {
            log.error("出题失败: sessionId={}", msg.getSessionId(), e);

            InterviewSession session = sessionMapper.selectById(msg.getSessionId());
            // 已是「已完成」说明业务其实成功了，异常发生在成功之后（例如幂等标记撞唯一键）。
            // 此时绝不能改判为失败，否则前端会看到状态从 1 抖到 2；也不需要重试。
            if (session != null && SessionStatus.DONE.matches(session.getStatus())) {
                log.warn("会话已完成，忽略本次异常: sessionId={}", msg.getSessionId());
                return;
            }
            updateStatus(msg.getSessionId(), SessionStatus.FAILED);
            // 抛出异常，交给 RocketMQ 重试机制
            throw new RuntimeException("出题任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 命中幂等时：把已有会话的题目复制到当前会话，并把状态置为完成。
     * 不能直接 return —— 否则当前会话永远停在 status=0，前端永远轮询不到结果。
     */
    private void reuseFromProcessed(InterviewMessage msg) {
        // 防重：当前会话已有题目说明之前复制过（消息重投），只补状态不再复制，否则题目会翻倍
        if (questionService.countBySession(msg.getSessionId()) > 0) {
            updateStatus(msg.getSessionId(), SessionStatus.DONE);
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
            updateStatus(msg.getSessionId(), SessionStatus.FAILED);
            return;
        }

        // 源会话 0 道题时不能置「完成」，否则前端拿到空题目的成功态
        if (questionService.countBySession(existing.getId()) == 0) {
            log.warn("可复用会话无题目: sessionId={}, 源 sessionId={}",
                    msg.getSessionId(), existing.getId());
            updateStatus(msg.getSessionId(), SessionStatus.FAILED);
            return;
        }

        int copied = questionService.copyTo(existing.getId(), msg.getSessionId()).size();
        updateStatus(msg.getSessionId(), SessionStatus.DONE);
        log.info("幂等复用完成: sessionId={}, 复用 {} 道题", msg.getSessionId(), copied);
    }

    /** 更新会话状态（原来这段在 4 个地方各写了一遍） */
    private void updateStatus(Long sessionId, SessionStatus status) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setStatus(status.getCode());
            sessionMapper.updateById(session);
        }
    }

    private boolean isAlreadyProcessed(InterviewMessage msg) {
        Long count = aiCallLogMapper.selectCount(
                new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getUserId, msg.getUserId())
                        .eq(AiCallLog::getCallMd5, msg.getJdMd5())
                        .eq(AiCallLog::getStatus, CALL_SUCCESS));
        return count != null && count > 0;
    }

    private void markProcessed(InterviewMessage msg) {
        AiCallLog logEntity = new AiCallLog();
        logEntity.setUserId(msg.getUserId());
        logEntity.setCallMd5(msg.getJdMd5());
        logEntity.setToolName("generateQuestions");
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
