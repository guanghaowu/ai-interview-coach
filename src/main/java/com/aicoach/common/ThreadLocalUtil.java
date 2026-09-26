package com.aicoach.common;

/**
 * 当前登录用户 ThreadLocal
 * 拦截器设置，业务层通过 get() 获取
 */
public class ThreadLocalUtil {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}