package com.aicoach.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项（不含题目正文，避免列表接口拖大响应体）
 */
@Data
public class SessionListItemVO {

    private Long sessionId;

    /** AI 提取的岗位名 */
    private String position;

    /** AI 提取的技术栈 */
    private String techStack;

    /** 0=AI 出题中 1=已完成 2=失败 */
    private Integer status;

    private LocalDateTime createdAt;

    /** 题目总数 */
    private long questionCount;

    /** 已评分完成的回答数 */
    private long answeredCount;
}
