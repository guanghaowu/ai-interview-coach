package com.aicoach.common;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
                // 熔断器已打开时，重试请求会被直接拒绝、根本不会发出去。
                // 此时继续退避重试纯属浪费：3 次重试要白等 1+2 秒，而结果早已注定。
                // 快速失败能让 MQ 外层重试链更快走完、更快落到死信兜底。
                if (isCircuitOpen(e)) {
                    log.warn("{} 熔断器已打开，放弃重试（快速失败）", taskName);
                    throw e;
                }
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

    /**
     * 判断异常链上是否存在「熔断器拒绝」。
     *
     * 需要沿 cause 链找：LangChain4j / Spring 都可能把底层异常包一层再抛出，
     * 只判断最外层类型会漏掉。
     */
    private static boolean isCircuitOpen(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof CallNotPermittedException) {
                return true;
            }
        }
        return false;
    }
}
