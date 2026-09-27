package com.aicoach.mq;

import com.aicoach.ai.InterviewAiService;
import com.aicoach.common.RetryUtil;
import com.aicoach.dto.QuestionDTO;
import com.aicoach.dto.QuestionListDTO;
import com.aicoach.entity.AiCallLog;
import com.aicoach.entity.InterviewSession;
import com.aicoach.entity.Question;
import com.aicoach.mapper.AiCallLogMapper;
import com.aicoach.mapper.InterviewSessionMapper;
import com.aicoach.mapper.QuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;

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

    private final InterviewAiService interviewAiService;
    private final InterviewSessionMapper sessionMapper;
    private final QuestionMapper questionMapper;
    private final AiCallLogMapper aiCallLogMapper;

    @Override
    public void onMessage(InterviewMessage msg) {
        log.info("收到出题任务: sessionId={}, jdMd5={}", msg.getSessionId(), msg.getJdMd5());

        try {
            // 1. 幂等检查：同一 (userId, jdMd5) 已成功处理过 → 复用已有题目。
            //    注意：不能直接 return，否则当前会话永远停在 status=0，前端轮询不到结果。
            if (isAlreadyProcessed(msg)) {
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

            // 3. 落库题目
            int order = 1;
            for (QuestionDTO q : result.getQuestions()) {
                Question entity = new Question();
                entity.setSessionId(msg.getSessionId());
                entity.setType(q.getType());
                entity.setContent(q.getContent());
                entity.setDifficulty(q.getDifficulty());
                entity.setSortOrder(order++);
                questionMapper.insert(entity);
            }

            // 4. 更新会话状态为「已完成」
            InterviewSession session = sessionMapper.selectById(msg.getSessionId());
            if (session != null) {
                session.setStatus(1);
                sessionMapper.updateById(session);
            }

            // 5. 记录幂等标记
            markProcessed(msg);

            log.info("出题完成: sessionId={}, 题目数={}", msg.getSessionId(), result.getQuestions().size());

        } catch (Exception e) {
            log.error("出题失败: sessionId={}", msg.getSessionId(), e);
            // 标记会话为失败
            InterviewSession session = sessionMapper.selectById(msg.getSessionId());
            if (session != null) {
                session.setStatus(2);
                sessionMapper.updateById(session);
            }
            // 抛出异常，交给 RocketMQ 重试机制
            throw new RuntimeException("出题任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 命中幂等时：把已有会话的题目复制到当前会话，并把状态置为完成。
     * 不能直接 return —— 否则当前会话永远停在 status=0，前端永远轮询不到结果。
     */
    private void reuseFromProcessed(InterviewMessage msg) {
        InterviewSession existing = sessionMapper.selectOne(
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getUserId, msg.getUserId())
                        .eq(InterviewSession::getJdMd5, msg.getJdMd5())
                        .eq(InterviewSession::getStatus, 1)
                        .ne(InterviewSession::getId, msg.getSessionId())
                        .orderByDesc(InterviewSession::getId)
                        .last("LIMIT 1"));

        if (existing == null) {
            log.warn("命中幂等但找不到可复用的会话: sessionId={}", msg.getSessionId());
            InterviewSession cur = sessionMapper.selectById(msg.getSessionId());
            if (cur != null) {
                cur.setStatus(2);
                sessionMapper.updateById(cur);
            }
            return;
        }

        List<Question> src = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getSessionId, existing.getId())
                        .orderByAsc(Question::getSortOrder));

        for (Question q : src) {
            Question copy = new Question();
            copy.setSessionId(msg.getSessionId());
            copy.setType(q.getType());
            copy.setContent(q.getContent());
            copy.setDifficulty(q.getDifficulty());
            copy.setSortOrder(q.getSortOrder());
            questionMapper.insert(copy);
        }

        InterviewSession cur = sessionMapper.selectById(msg.getSessionId());
        if (cur != null) {
            cur.setStatus(1);
            sessionMapper.updateById(cur);
        }
        log.info("幂等复用完成: sessionId={}, 复用 {} 道题", msg.getSessionId(), src.size());
    }

    private boolean isAlreadyProcessed(InterviewMessage msg) {
        Long count = aiCallLogMapper.selectCount(
                new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getUserId, msg.getUserId())
                        .eq(AiCallLog::getCallMd5, msg.getJdMd5())
                        .eq(AiCallLog::getStatus, 1));
        return count != null && count > 0;
    }

    private void markProcessed(InterviewMessage msg) {
        AiCallLog logEntity = new AiCallLog();
        logEntity.setUserId(msg.getUserId());
        logEntity.setCallMd5(msg.getJdMd5());
        logEntity.setToolName("generateQuestions");
        logEntity.setStatus(1);
        aiCallLogMapper.insert(logEntity);
    }
}