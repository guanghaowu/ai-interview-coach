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

    private Integer sortOrder;
}