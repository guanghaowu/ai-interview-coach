package com.aicoach.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 出题任务消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InterviewMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    private Long userId;

    private String jdContent;

    /** JD 的 MD5，用作幂等键 */
    private String jdMd5;
}