package com.aicoach.dto;

import lombok.Data;

/**
 * AI 返回的评分反馈结构
 */
@Data
public class FeedbackDTO {

    /** 评分 1-10 */
    private Integer score;

    /** 优点 */
    private String pros;

    /** 缺点 */
    private String cons;

    /** 改进建议 */
    private String suggestions;
}