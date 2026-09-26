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
 * AI 评分反馈（注意：feedback 表无逻辑删除字段）
 */
@Data
@TableName("feedback")
public class Feedback implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long answerId;

    /** 优点 */
    private String pros;

    /** 缺点 */
    private String cons;

    /** 改进建议 */
    private String suggestions;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}