package com.aicoach.mq;

import cn.hutool.json.JSONUtil;
import com.aicoach.ai.InterviewAiService;
import com.aicoach.common.RetryUtil;
import com.aicoach.dto.FeedbackDTO;
import com.aicoach.entity.Answer;
import com.aicoach.entity.Feedback;
import com.aicoach.mapper.AnswerMapper;
import com.aicoach.mapper.FeedbackMapper;
import com.aicoach.service.SessionMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评分任务消费者
 *
 * 与出题同理：AI 评分耗时 5-15 秒，必须放到消费端异步执行，
 * 否则 HTTP 线程会被长时间占用，并发上来会拖垮整个服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = InterviewProducer.ANSWER_TOPIC,
        consumerGroup = "interview-answer-consumer-group"
)
public class AnswerConsumer implements RocketMQListener<AnswerMessage> {

    private final InterviewAiService interviewAiService;
    private final AnswerMapper answerMapper;
    private final FeedbackMapper feedbackMapper;
    private final SessionMemoryService sessionMemoryService;

    @Override
    public void onMessage(AnswerMessage msg) {
        log.info("收到评分任务: answerId={}", msg.getAnswerId());

        try {
            Answer answer = answerMapper.selectById(msg.getAnswerId());
            if (answer == null) {
                log.warn("回答不存在，跳过: answerId={}", msg.getAnswerId());
                return;
            }
            // 已评分则跳过（MQ 重投场景），避免重复调 AI
            if (Integer.valueOf(1).equals(answer.getStatus())) {
                log.info("该回答已评分，跳过: answerId={}", msg.getAnswerId());
                return;
            }

            // 取会话历史，让 AI 基于上下文评分
            List<String> history = sessionMemoryService.getRecent(msg.getSessionId(), 10);
            String historyText = history.isEmpty() ? "（无历史，这是第一轮）" : String.join("\n", history);

            // 指数退避重试：1s → 2s → 4s
            FeedbackDTO fb = RetryUtil.retryWithBackoff(
                    "AI评分", 3, 1000,
                    () -> interviewAiService.evaluateAnswer(
                            msg.getQuestionContent(), msg.getAnswerContent(), historyText));

            if (fb == null || fb.getScore() == null) {
                throw new IllegalStateException("AI 返回评分为空");
            }

            // 落库反馈
            Feedback feedback = new Feedback();
            feedback.setAnswerId(answer.getId());
            feedback.setPros(fb.getPros());
            feedback.setCons(fb.getCons());
            feedback.setSuggestions(fb.getSuggestions());
            feedbackMapper.insert(feedback);

            // 更新回答为「已评分」
            answer.setScore(fb.getScore());
            answer.setFeedbackId(feedback.getId());
            answer.setStatus(1);
            answerMapper.updateById(answer);

            // AI 反馈写入会话记忆
            sessionMemoryService.append(msg.getSessionId(), "assistant", JSONUtil.toJsonStr(fb));

            log.info("评分完成: answerId={}, score={}", answer.getId(), fb.getScore());

        } catch (Exception e) {
            log.error("评分失败: answerId={}", msg.getAnswerId(), e);
            markFailed(msg.getAnswerId());
            // 抛出异常交给 RocketMQ 重试机制
            throw new RuntimeException("评分任务失败: " + e.getMessage(), e);
        }
    }

    private void markFailed(Long answerId) {
        Answer answer = answerMapper.selectById(answerId);
        if (answer != null) {
            answer.setStatus(2);
            answerMapper.updateById(answer);
        }
    }
}