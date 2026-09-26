package com.aicoach.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解（基于 Redis + Lua 令牌桶）
 *
 * 用法：
 * <pre>
 * &#64;RateLimit(key = "rate:interview", window = 86400, limit = 10, type = LimitType.USER)
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** Redis key 前缀 */
    String key() default "rate:api";

    /** 时间窗口（秒） */
    int window() default 60;

    /** 窗口内允许的最大请求数（= 令牌桶容量） */
    int limit() default 10;

    /** 限流维度 */
    LimitType type() default LimitType.IP;
}