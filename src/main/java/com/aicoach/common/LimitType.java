package com.aicoach.common;

/**
 * 限流维度
 */
public enum LimitType {

    /** 按 IP 限流 */
    IP,

    /** 按登录用户限流 */
    USER
}