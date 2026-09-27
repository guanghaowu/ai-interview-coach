package com.aicoach.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 失败任务（死信兜底 + 重投）
 *
 * <h3>它补的是哪一环</h3>
 * 在它之前，MQ 消费失败后的链路是断的：RocketMQ 自动重试 → 重试耗尽 → 进
 * {@code %DLQ%<consumerGroup>} 死信队列 → **没有任何人消费，消息就静静躺在那儿**。
 * 用户侧看到的是会话/回答永久停在「失败」，没有任何恢复路径。
 *
 * <h3>为什么不直接「消费死信时就重投」</h3>
 * 死信消息被消费的那一刻，上游往往**还处于故障中**（否则它压根不会成为死信）。
 * 立刻重投大概率再失败一次，白白消耗一次重试额度。
 * 所以这里只做「落库记录」，重投交给定时任务在**之后**做——
 * 拉开时间间隔，给上游恢复留出窗口。
 *
 * <h3>为什么存原始 payload 而不是只存业务 ID</h3>
 * 重投要发回 MQ，消息体必须原样还原。只存 {@code bizId} 的话，
 * 重投时得重新查库拼装消息，而消息体里可能有当时才有的字段
 * （例如 {@code AnswerMessage.questionContent} 是提交那一刻的题目原文）。
 */
@Data
@TableName("failed_task")
public class FailedTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：INTERVIEW / ANSWER，见 {@link com.aicoach.constant.FailedTaskType} */
    private String taskType;

    /** 原始 topic，重投时原样发回 */
    private String topic;

    /** 业务主键：出题=sessionId，评分=answerId。与 taskType 组成唯一键 */
    private Long bizId;

    private Long userId;

    /** 原始消息体 JSON，重投时反序列化后发回 */
    private String payload;

    /** 最后一次失败原因（截断存储，仅供排查） */
    private String errorMsg;

    /** 已重投次数 */
    private Integer retryCount;

    /** 0=待重投 1=重投成功 2=已放弃，见 {@link com.aicoach.constant.FailedTaskStatus} */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
