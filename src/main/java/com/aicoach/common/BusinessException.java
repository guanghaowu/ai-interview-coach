package com.aicoach.common;

import lombok.Getter;

/**
 * 业务异常
 *
 * 默认错误码 500，业务异常可自定义 4xx（如 401 未登录、403 无权限）
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}