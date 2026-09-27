package com.aicoach.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模拟面试会话
 */
@Data
@TableName("interview_session")
public class InterviewSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** JD 内容的 MD5（用于复用题目） */
    private String jdMd5;

    /** 原始 JD */
    private String jdContent;

    /** AI 提取的岗位名 */
    private String position;

    /** AI 提取的技术栈 */
    private String techStack;

    /** 0=AI 出题中 1=已完成 2=失败（语义以 constant.SessionStatus 为准） */
    private Integer status;

    /** AgentLoop 实际执行的出题轮数（1=首轮通过，2=触发过一次定向修订，0=未跑或降级） */
    private Integer agentRounds;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}