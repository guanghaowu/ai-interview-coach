package com.aicoach.service;

import com.aicoach.constant.FailedTaskStatus;
import com.aicoach.constant.FailedTaskType;
import com.aicoach.entity.FailedTask;
import com.aicoach.mapper.FailedTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 失败任务服务（死信兜底）
 *
 * <p>只负责失败任务的持久化与查询，**不做重投**——重投的时机与编排在
 * {@link com.aicoach.job.FailedTaskRetryJob}，这样「记录」与「重试策略」分开，
 * 改重试策略不必碰落库逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailedTaskService {

    /** error_msg 列宽 500，超出截断，避免插入时被 MySQL 拒绝 */
    private static final int ERROR_MSG_MAX = 500;

    private final FailedTaskMapper failedTaskMapper;

    /**
     * 记录一条失败任务。
     *
     * <p>刻意不加 {@code @Transactional}：单条 insert 本身就是原子的，
     * 而这里必须**吞掉唯一键冲突**——同一条死信消息可能被重复投递
     * （DLQ 里的消息同样会按消费者重试策略被再次投递），
     * 若让异常抛出，死信消费者会再次失败，反而制造出新的死信，形成死循环。
     *
     * <p>唯一键是 {@code (task_type, biz_id)} 而不是消息 ID：MQ 重试会生成新的消息 ID，
     * 用消息 ID 去重等于没去重；而「同一个会话的出题任务」「同一条回答的评分任务」
     * 在业务上天然只会成功一次，用它做键才能真的收敛成一条记录。
     */
    public void record(FailedTaskType type, String topic,
                       Long bizId, Long userId, String payload, String errorMsg) {
        FailedTask task = new FailedTask();
        task.setTaskType(type.getValue());
        task.setTopic(topic);
        task.setBizId(bizId);
        task.setUserId(userId);
        task.setPayload(payload);
        task.setErrorMsg(truncate(errorMsg));
        task.setRetryCount(0);
        task.setStatus(FailedTaskStatus.PENDING.getCode());

        try {
            failedTaskMapper.insert(task);
            log.warn("失败任务已记录（等待重投）: type={}, bizId={}, error={}",
                    type.getValue(), bizId, truncate(errorMsg));
        } catch (DuplicateKeyException e) {
            log.info("失败任务已存在（同一业务重复进入死信），忽略: type={}, bizId={}",
                    type.getValue(), bizId);
        }
    }

    /** 查出所有「待重投且未超次数」的任务，按 id 升序（先失败先重投） */
    public List<FailedTask> listRetryable(int maxRetry) {
        return failedTaskMapper.selectList(
                new LambdaQueryWrapper<FailedTask>()
                        .eq(FailedTask::getStatus, FailedTaskStatus.PENDING.getCode())
                        .lt(FailedTask::getRetryCount, maxRetry)
                        .orderByAsc(FailedTask::getId));
    }

    /**
     * 查出所有「待重投」任务（含已超次数的）。
     *
     * 用于先做一轮「业务侧是否已经成功」的对账：任务在重投前可能已被
     * 用户手动重试成功，此时不该再重投，直接标记成功即可。
     */
    public List<FailedTask> listPending() {
        return failedTaskMapper.selectList(
                new LambdaQueryWrapper<FailedTask>()
                        .eq(FailedTask::getStatus, FailedTaskStatus.PENDING.getCode())
                        .orderByAsc(FailedTask::getId));
    }

    /** 标记重投成功 */
    public void markSucceeded(FailedTask task) {
        task.setStatus(FailedTaskStatus.SUCCEEDED.getCode());
        failedTaskMapper.updateById(task);
        log.info("失败任务重投成功: id={}, type={}, bizId={}, 重投次数={}",
                task.getId(), task.getTaskType(), task.getBizId(), task.getRetryCount());
    }

    /** 标记放弃（重投次数用尽） */
    public void markAbandoned(FailedTask task) {
        task.setStatus(FailedTaskStatus.ABANDONED.getCode());
        failedTaskMapper.updateById(task);
        log.error("失败任务重投次数用尽，已放弃（需人工介入）: id={}, type={}, bizId={}, 已重投={}次",
                task.getId(), task.getTaskType(), task.getBizId(), task.getRetryCount());
    }

    /** 重投一次：次数 +1 并落库 */
    public void incrementRetry(FailedTask task) {
        task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        failedTaskMapper.updateById(task);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= ERROR_MSG_MAX ? s : s.substring(0, ERROR_MSG_MAX);
    }
}
