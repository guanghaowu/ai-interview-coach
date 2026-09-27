package com.aicoach.mq;

import com.aicoach.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 出题任务生产者
 *
 * 把长耗时的 AI 出题任务投递到 MQ，主接口立即返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public static final String TOPIC = "interview-generate-topic";

    /** 评分任务 topic */
    public static final String ANSWER_TOPIC = "interview-answer-topic";

    /**
     * 投递出题任务（同步发送，保证不丢）
     */
    public void sendGenerateTask(InterviewMessage msg) {
        try {
            rocketMQTemplate.syncSend(TOPIC, msg);
            log.info("MQ 消息投递成功: sessionId={}, jdMd5={}", msg.getSessionId(), msg.getJdMd5());
        } catch (Exception e) {
            log.error("MQ 消息投递失败: sessionId={}", msg.getSessionId(), e);
            throw new BusinessException("任务投递失败，请稍后重试");
        }
    }

    /**
     * 投递评分任务（同步发送，保证不丢）
     */
    public void sendAnswerTask(AnswerMessage msg) {
        try {
            rocketMQTemplate.syncSend(ANSWER_TOPIC, msg);
            log.info("MQ 评分消息投递成功: answerId={}", msg.getAnswerId());
        } catch (Exception e) {
            log.error("MQ 评分消息投递失败: answerId={}", msg.getAnswerId(), e);
            throw new BusinessException("评分任务投递失败，请稍后重试");
        }
    }
}