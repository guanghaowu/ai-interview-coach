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
 * 出题任务的死信消费者
 *
 * <h3>它在整条兜底链路里的位置</h3>
 * <pre>
 * InterviewConsumer 失败
 *   → RocketMQ 自动重试（最多 maxReconsumeTimes 次，间隔递增）
 *   → 仍失败 → broker 投递到死信 topic %DLQ%interview-consumer-group
 *   → 【本类】把死信消息落库到 failed_task（不做重投）
 *   → FailedTaskRetryJob 定时重投（拉开时间，等上游恢复）
 * </pre>
 *
 * <h3>为什么这里只落库、不立刻重投</h3>
 * 死信被消费的这一刻，上游往往**还在故障中**（否则它不会走到死信）。
 * 立刻重投大概率再失败一次，白白烧掉一次重试额度。落库 + 延后重投
 * 才给上游留出了恢复窗口。
 *
 * <h3>为什么必须吞掉所有异常</h3>
 * 死信消费者自己也有重试与死信机制。若本类抛异常，会生成「死信的死信」，
 * 形成无意义的循环。落库失败只记日志——消息本身还在死信 topic 里，没有丢。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = InterviewDlqConsumer.DLQ_TOPIC,
        consumerGroup = "interview-dlq-consumer-group"
)
public class InterviewDlqConsumer implements RocketMQListener<String> {

    /**
     * 死信 topic 命名规则：{@code %DLQ%} + 原消费者的 consumerGroup。
     * 由 broker 在消息重试耗尽时自动创建，无需手工建。
     */
    public static final String DLQ_TOPIC = "%DLQ%interview-consumer-group";

    private final FailedTaskService failedTaskService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String body) {
        log.error("出题任务进入死信队列，准备落库等待重投: body={}", body);
        try {
            InterviewMessage msg = objectMapper.readValue(body, InterviewMessage.class);
            failedTaskService.record(FailedTaskType.INTERVIEW, InterviewProducer.TOPIC,
                    msg.getSessionId(), msg.getUserId(), body,
                    "MQ 重试耗尽，进入死信队列");
        } catch (Exception e) {
            // 解析失败也要吞掉：可能是历史遗留的异构消息，不该阻塞后续死信的处理
            log.error("死信消息解析失败，已忽略: body={}", body, e);
        }
    }
}
