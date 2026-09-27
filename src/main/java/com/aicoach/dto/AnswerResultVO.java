package com.aicoach.dto;

import lombok.Data;

/**
 * 提交回答后的返回：评分 + 反馈
 */
@Data
public class AnswerResultVO {

    private Long answerId;

    /** 0=评分中 1=已完成 2=失败（异步化后靠这个字段轮询） */
    private Integer status;

    private Integer score;

    private String pros;

    private String cons;

    private String suggestions;
}