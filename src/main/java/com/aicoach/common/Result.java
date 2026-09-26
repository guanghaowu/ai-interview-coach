package com.aicoach.common;

import lombok.Data;

/**
 * 统一响应格式
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> {

    /** 状态码：0=成功，非 0=失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 响应数据 */
    private T data;

    public Result() {}

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}