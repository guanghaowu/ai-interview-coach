package com.aicoach.common;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 重试工具：指数退避
 *
 * 用于三方 API 网络抖动场景。重试间隔按 1s → 2s → 4s 递增。
 */
@Slf4j
public class RetryUtil {

    private RetryUtil() {}

    /**
     * 带指数退避的重试
     *
     * @param taskName      任务名（日志用）
     * @param maxAttempts   最大尝试次数
     * @param initialDelayMs 首次重试间隔（毫秒），之后每次翻倍
     * @param action        要执行的动作
     */
    public static <T> T retryWithBackoff(String taskName, int maxAttempts,
                                         long initialDelayMs, Supplier<T> action) {
        long delay = initialDelayMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    log.error("{} 重试 {} 次后仍失败", taskName, maxAttempts, e);
                    throw e;
                }
                log.warn("{} 第 {}/{} 次失败，{}ms 后重试：{}",
                        taskName, attempt, maxAttempts, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试被中断", ie);
                }
                delay *= 2;   // 指数退避
            }
        }
        throw new IllegalStateException("unreachable");
    }
}