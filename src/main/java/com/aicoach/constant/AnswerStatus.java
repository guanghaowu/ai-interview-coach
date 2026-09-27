package com.aicoach.constant;

/**
 * 回答的评分状态
 *
 * 与 SessionStatus 是两套独立的语义（虽然取值都是 0/1/2），
 * 分开定义避免混用——「会话完成」和「回答已评分」不是一回事。
 */
public enum AnswerStatus {

    /** 已落库，等待 MQ 消费端评分 */
    PENDING(0, "待评分"),

    /** 评分完成，feedback 已落库 */
    GRADED(1, "已评分"),

    /** 评分失败（重试耗尽） */
    FAILED(2, "评分失败");

    private final int code;
    private final String desc;

    AnswerStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 判断给定状态码是否等于本枚举；code 为 null 时返回 false */
    public boolean matches(Integer code) {
        return Integer.valueOf(this.code).equals(code);
    }
}
