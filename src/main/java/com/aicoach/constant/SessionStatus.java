package com.aicoach.constant;

/**
 * 会话状态
 *
 * 用枚举替代散落的裸 int（0/1/2）。这类「数字含义」只有集中定义才好防错——
 * 项目早期 init.sql 的注释就把语义写反过，代码里再靠人记，迟早出错。
 */
public enum SessionStatus {

    /** AI 出题中（主接口已返回，等待 MQ 消费） */
    GENERATING(0, "AI 出题中"),

    /** 出题完成，前端轮询可拿到题目 */
    DONE(1, "已完成"),

    /** 出题失败（重试耗尽） */
    FAILED(2, "失败");

    private final int code;
    private final String desc;

    SessionStatus(int code, String desc) {
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
