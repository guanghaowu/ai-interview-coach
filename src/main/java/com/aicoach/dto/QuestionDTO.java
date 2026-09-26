package com.aicoach.dto;

import lombok.Data;

/**
 * AI 返回的单道题目结构
 */
@Data
public class QuestionDTO {

    /** 1=编程 2=场景 3=项目 4=八股 */
    private Integer type;

    /** 题目内容 */
    private String content;

    /** 1=易 2=中 3=难 */
    private Integer difficulty;
}