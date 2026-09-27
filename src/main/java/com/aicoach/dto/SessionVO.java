package com.aicoach.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话详情返回
 */
@Data
public class SessionVO {

    private Long sessionId;

    private String position;

    private String techStack;

    /** 0=AI 出题中 1=已完成 2=失败 */
    private Integer status;

    private LocalDateTime createdAt;

    private List<QuestionVO> questions;
}