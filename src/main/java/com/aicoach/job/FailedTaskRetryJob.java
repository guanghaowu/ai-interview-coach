package com.aicoach.job;

import com.aicoach.constant.AnswerStatus;
import com.aicoach.constant.FailedTaskType;
import com.aicoach.constant.SessionStatus;
import com.aicoach.entity.Answer;
import com.aicoach.entity.FailedTask;
import com.aicoach.entity.InterviewSession;
import com.aicoach.mapper.AnswerMapper;
import com.aicoach.mapper.InterviewSessionMapper;
import com.aicoach.mq.AnswerMessage;
import com.aicoach.mq.InterviewMessage;
import com.aicoach.mq.InterviewProducer;
import com.aicoach.service.FailedTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 失败任务重投（死信兜底链路的最后一环）
 *
 * <h3>为什么要有定时重投，而不是在死信消费者里直接重投</h3>
 * 死信产生的那一刻，上游多半还没恢复。立刻重投等于再失败一次。
 * 定时任务把重投推迟到一个**新的时间点**，给上游留出恢复窗口——
 * 这是「重试时机」和「重试次数」两件事，前者同样重要。
 *
 * <h3>对账优先于重投</h3>
 * 每条待处理任务先查一次**业务侧的真实状态**，而不是无脑重投：
 * <ul>
 *   <li>业务已成功 → 直接标记成功。任务可能已被用户手动重试、或 MQ 重试期间自己成功了，
 *       此时再重投就是纯粹的重复调用（会真烧一次 AI Token）。</li>
 *   <li>业务仍在处理中 → 跳过。消息已经重新发出去了，等下一轮再对账。</li>
 *   <li>业务仍是失败态 → 才重投。</li>
 * </ul>
 * 这个「先对账再动作」的顺序，是让重投不产生副作用的关键。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailedTaskRetryJob {

    /** 自动重投的最大次数，超过则放弃并告警（留给人工介入） */
    private static final int MAX_RETRY = 3;

    private final FailedTaskService failedTaskService;
    private final InterviewProducer interviewProducer;
    private final InterviewSessionMapper sessionMapper;
    private final AnswerMapper answerMapper;
    private final ObjectMapper objectMapper;

    /**
     * 定时扫描并重投失败任务。
     *
     * <p>{@code fixedDelay} 而非 {@code fixedRate}：上一次执行（含重投耗时）结束后
     * 再开始计时，避免任务堆叠。间隔可配，默认 5 分钟——与 MQ 重试的秒级间隔错开，
     * 形成「秒级抖动重试 + 分钟级兜底重投」两层节奏。
     */
    @Scheduled(
            fixedDelayString = "${app.failed-task.retry-interval-ms:300000}",
            initialDelayString = "${app.failed-task.initial-delay-ms:60000}")
    public void retryFailedTasks() {
        List<FailedTask> pending = failedTaskService.listPending();
        if (pending.isEmpty()) {
            return;
        }

        int succeeded = 0;
        int redelivered = 0;
        int abandoned = 0;
        int skipped = 0;

        for (FailedTask task : pending) {
            try {
                switch (resolveOutcome(task)) {
                    case DONE -> {
                        failedTaskService.markSucceeded(task);
                        succeeded++;
                    }
                    case FAILED -> {
                        if (task.getRetryCount() != null && task.getRetryCount() >= MAX_RETRY) {
                            failedTaskService.markAbandoned(task);
                            abandoned++;
                        } else if (redeliver(task)) {
                            failedTaskService.incrementRetry(task);
                            redelivered++;
                        }
                    }
                    // 处理中（消息已重新投出，等消费结果）：本轮不做任何动作
                    case IN_PROGRESS -> skipped++;
                }
            } catch (Exception e) {
                // 单条任务出错不能影响其余任务：否则一条坏数据会让整个兜底机制停摆
                log.error("失败任务处理异常，跳过: id={}, type={}, bizId={}",
                        task.getId(), task.getTaskType(), task.getBizId(), e);
            }
        }

        log.info("失败任务扫描完成: 待处理={}, 对账成功={}, 已重投={}, 放弃={}, 处理中跳过={}",
                pending.size(), succeeded, redelivered, abandoned, skipped);
    }

    /** 业务侧的真实结果 */
    private enum Outcome {
        /** 业务已成功完成 */
        DONE,
        /** 业务处于失败态，需要重投 */
        FAILED,
        /** 业务正在处理中（消息已投出），本轮跳过 */
        IN_PROGRESS
    }

    private Outcome resolveOutcome(FailedTask task) {
        if (FailedTaskType.INTERVIEW.getValue().equals(task.getTaskType())) {
            InterviewSession session = sessionMapper.selectById(task.getBizId());
            if (session == null) {
                // 会话被删了（业务上不该发生），没有重投的意义
                return Outcome.DONE;
            }
            if (SessionStatus.DONE.matches(session.getStatus())) {
                return Outcome.DONE;
            }
            return SessionStatus.FAILED.matches(session.getStatus())
                    ? Outcome.FAILED : Outcome.IN_PROGRESS;
        }

        if (FailedTaskType.ANSWER.getValue().equals(task.getTaskType())) {
            Answer answer = answerMapper.selectById(task.getBizId());
            if (answer == null) {
                return Outcome.DONE;
            }
            if (AnswerStatus.GRADED.matches(answer.getStatus())) {
                return Outcome.DONE;
            }
            return AnswerStatus.FAILED.matches(answer.getStatus())
                    ? Outcome.FAILED : Outcome.IN_PROGRESS;
        }

        log.warn("未知的失败任务类型，按已完成处理: type={}", task.getTaskType());
        return Outcome.DONE;
    }

    /**
     * 按类型把原始 payload 反序列化后重投回原 topic。
     *
     * @return 是否投递成功（投递失败不累加次数，留给下一轮再试）
     */
    private boolean redeliver(FailedTask task) {
        try {
            if (FailedTaskType.INTERVIEW.getValue().equals(task.getTaskType())) {
                InterviewMessage msg = objectMapper.readValue(task.getPayload(), InterviewMessage.class);
                interviewProducer.sendGenerateTask(msg);
            } else {
                AnswerMessage msg = objectMapper.readValue(task.getPayload(), AnswerMessage.class);
                interviewProducer.sendAnswerTask(msg);
            }
            log.info("失败任务已重投: id={}, type={}, bizId={}, 第 {} 次",
                    task.getId(), task.getTaskType(), task.getBizId(),
                    (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
            return true;
        } catch (Exception e) {
            log.error("失败任务重投失败: id={}, type={}, bizId={}",
                    task.getId(), task.getTaskType(), task.getBizId(), e);
            return false;
        }
    }
}
