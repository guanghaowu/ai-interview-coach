package com.aicoach.constant;

/**
 * 题目难度
 *
 * 与 InterviewAiService 提示词中的约定一致（1=易 2=中 3=难）。
 */
public enum Difficulty {

    EASY(1, "易"),
    MEDIUM(2, "中"),
    HARD(3, "难");

    private final int code;
    private final String desc;

    Difficulty(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 归一化 AI 返回的难度。
     * 非法值兜底为中等，避免「难度=null/99」这类脏数据落库。
     */
    public static int normalize(Integer code) {
        for (Difficulty d : values()) {
            if (Integer.valueOf(d.code).equals(code)) {
                return d.code;
            }
        }
        return MEDIUM.code;
    }
}
