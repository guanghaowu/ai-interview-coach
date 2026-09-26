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
 * AI 调用日志（用于幂等去重 + Token 成本统计）
 */
@Data
@TableName("ai_call_log")
public class AiCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 请求参数 MD5（幂等键） */
    private String callMd5;

    private String toolName;

    private Integer promptTokens;

    private Integer totalTokens;

    /** 0=失败 1=成功 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}