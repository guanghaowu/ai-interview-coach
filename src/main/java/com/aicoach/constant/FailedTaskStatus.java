package com.aicoach.constant;

/**
 * 失败任务状态（死信兜底后的重投生命周期）
 *
 * PENDING → SUCCEEDED（重投后业务侧确认完成）
 *         ↘ ABANDONED（重投次数用尽仍失败，需人工介入）
 */
public enum FailedTaskStatus {

    /** 已落库、等待重投 */
    PENDING(0, "待重投"),

    /** 重投后业务侧已确认成功 */
    SUCCEEDED(1, "重投成功"),

    /** 重投次数用尽，放弃自动重试 */
    ABANDONED(2, "已放弃");

    private final int code;
    private final String desc;

    FailedTaskStatus(int code, String desc) {
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
