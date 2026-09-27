package com.aicoach.constant;

/**
 * 面试题型
 *
 * 取值与 InterviewAiService 提示词里的约定保持一致（1=编程 2=场景 3=项目 4=八股）。
 * 注意：提示词是编译期常量注解，没法直接引用枚举，改这里时记得同步改提示词。
 */
public enum QuestionType {

    CODING(1, "编程题"),
    SCENARIO(2, "场景题"),
    PROJECT(3, "项目题"),
    BASIC(4, "八股题");

    private final int code;
    private final String desc;

    QuestionType(int code, String desc) {
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
     * 归一化 AI 返回的题型。
     * 大模型的结构化输出并不总是可靠（可能给 null 或越界值），
     * 直接落库会污染数据，这里统一兜底为八股题。
     */
    public static int normalize(Integer code) {
        for (QuestionType t : values()) {
            if (Integer.valueOf(t.code).equals(code)) {
                return t.code;
            }
        }
        return BASIC.code;
    }
}
