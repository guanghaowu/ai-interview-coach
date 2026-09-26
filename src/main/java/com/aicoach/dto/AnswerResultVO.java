package com.aicoach.dto;

import lombok.Data;

/**
 * 提交回答后的返回：评分 + 反馈
 */
@Data
public class AnswerResultVO {

    private Long answerId;

    private Integer score;

    private String pros;

    private String cons;

    private String suggestions;
}