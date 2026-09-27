package com.aicoach.constant;

/**
 * 失败任务类型（决定重投时往哪个 topic 发、以及用哪个业务字段判断是否已成功）
 */
public enum FailedTaskType {

    /** 出题任务：重投到 interview-generate-topic，用会话状态判断成功 */
    INTERVIEW("INTERVIEW"),

    /** 评分任务：重投到 interview-answer-topic，用回答状态判断成功 */
    ANSWER("ANSWER");

    private final String value;

    FailedTaskType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
