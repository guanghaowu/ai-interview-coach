package com.aicoach.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

/**
 * 限流切面（Redis + Lua 令牌桶，保证「取令牌 + 回写」原子性）
 *
 * 令牌桶 vs 滑动窗口：
 * - 令牌桶允许突发流量（用户短时间连续操作），更贴合真实用户行为
 * - 滑动窗口适合严格限流（如防爬虫）
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit);

        // 令牌补充速率 = 容量 / 窗口秒数。
        // 必须用 double 保留小数！若取整（如 10/86400 → 1），补充速率会远大于消耗速率，限流将完全失效。
        double rate = (double) rateLimit.limit() / Math.max(1, rateLimit.window());

        Long allowed = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(rateLimit.limit()),
                String.valueOf(rate),
                String.valueOf(System.currentTimeMillis()),
                "1");

        if (allowed == null || allowed == 0L) {
            log.warn("触发限流: key={}, limit={}/{}s", key, rateLimit.limit(), rateLimit.window());
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        return pjp.proceed();
    }

    private String buildKey(RateLimit rateLimit) {
        if (rateLimit.type() == LimitType.USER) {
            Long userId = ThreadLocalUtil.get();
            return rateLimit.key() + ":user:" + (userId == null ? "anonymous" : userId);
        }
        return rateLimit.key() + ":ip:" + currentIp();
    }

    private String currentIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}