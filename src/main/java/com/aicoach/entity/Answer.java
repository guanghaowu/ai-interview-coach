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
 * 用户回答
 */
@Data
@TableName("answer")
public class Answer implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;

    private Long sessionId;

    private Long userId;

    /** 回答内容 */
    private String content;

    /** AI 评分 1-10 */
    private Integer score;

    /** 关联 feedback.id */
    private Long feedbackId;

    /** 追问轮次 */
    private Integer round;

    /** 0=待评分 1=已评分 2=失败 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}