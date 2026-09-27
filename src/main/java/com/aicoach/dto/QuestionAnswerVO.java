package com.aicoach.dto;

import lombok.Data;

/**
 * 会话详情里的一道题：题干 + 该题最新一次回答 + 评分反馈
 *
 * 平铺成一个对象，前端渲染列表时不用再自己拼两张表。
 */
@Data
public class QuestionAnswerVO {

    private Long questionId;

    /** 1=编程 2=场景 3=项目 4=八股 */
    private Integer type;

    private String content;

    /** 1=易 2=中 3=难 */
    private Integer difficulty;

    /** 考察维度（Planner 拆解），前端可按此分组 */
    private String dimension;

    private Integer sortOrder;

    // ===== 以下为回答与反馈，未作答时全为 null =====

    private Long answerId;

    private String answerContent;

    /** 0=待评分 1=已评分 2=失败 */
    private Integer answerStatus;

    private Integer score;

    private String pros;

    private String cons;

    private String suggestions;
}
