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

    /** 1=进行中 2=已结束 */
    private Integer status;

    private LocalDateTime createdAt;

    private List<QuestionVO> questions;
}