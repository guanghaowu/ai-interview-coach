package com.aicoach.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 评分任务消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long answerId;

    private Long questionId;

    private Long sessionId;

    private Long userId;

    /** 题目原文（避免消费者再查一次库） */
    private String questionContent;

    /** 用户回答 */
    private String answerContent;
}