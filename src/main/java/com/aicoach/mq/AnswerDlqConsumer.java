package com.aicoach.mq;

import com.aicoach.constant.FailedTaskType;
import com.aicoach.service.FailedTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 评分任务的死信消费者
 *
 * 与 {@link InterviewDlqConsumer} 同构，只是绑定的 consumerGroup 与消息体不同。
 * 之所以拆成两个类而不是一个类监听两个 topic：
 * {@code @RocketMQMessageListener.topic} 是单值，且两个 topic 的消息体类型不同，
 * 混在一个类里反而需要额外的类型判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = AnswerDlqConsumer.DLQ_TOPIC,
        consumerGroup = "answer-dlq-consumer-group"
)
public class AnswerDlqConsumer implements RocketMQListener<String> {

    /** {@code %DLQ%} + 原消费者 group（见 AnswerConsumer 的 consumerGroup） */
    public static final String DLQ_TOPIC = "%DLQ%interview-answer-consumer-group";

    private final FailedTaskService failedTaskService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String body) {
        log.error("评分任务进入死信队列，准备落库等待重投: body={}", body);
        try {
            AnswerMessage msg = objectMapper.readValue(body, AnswerMessage.class);
            failedTaskService.record(FailedTaskType.ANSWER, InterviewProducer.ANSWER_TOPIC,
                    msg.getAnswerId(), msg.getUserId(), body,
                    "MQ 重试耗尽，进入死信队列");
        } catch (Exception e) {
            log.error("死信消息解析失败，已忽略: body={}", body, e);
        }
    }
}
