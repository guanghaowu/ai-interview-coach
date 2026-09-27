package com.aicoach.common;

import lombok.Getter;

/**
 * 业务异常
 *
 * 默认错误码 500，但**只应留给真正的服务端故障**（如 MQ 投递失败）。
 * 资源不存在（404）、未认证（401）、无权限（403）、参数/状态冲突（409）
 * 等客户端可纠正的错误，务必显式传入对应码——否则前端无法区分
 * 「你要的资源没有」和「服务器挂了」，排查时会被误导。
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