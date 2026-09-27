package com.aicoach.dto;

import lombok.Data;

/**
 * 题目返回
 */
@Data
public class QuestionVO {

    private Long id;

    /** 1=编程 2=场景 3=项目 4=八股 */
    private Integer type;

    private String content;

    /** 1=易 2=中 3=难 */
    private Integer difficulty;

    /** 考察维度（Planner 拆解），前端可按此分组 */
    private String dimension;

    private Integer sortOrder;
}