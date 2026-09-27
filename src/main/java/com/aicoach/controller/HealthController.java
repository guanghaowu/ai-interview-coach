package com.aicoach.controller;

import com.aicoach.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口
 *
 * 除了「进程还活着」，这里还暴露 **LLM 熔断器状态**：
 * 熔断打开时进程本身完全健康，但业务能力已经降级，
 * 只看「UP」是看不出来的。把它放进健康检查，
 * 才能在依赖故障时第一时间定位到「不是我的服务挂了，是上游挂了」。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "ai-interview-coach");
        result.put("version", "0.0.1-SNAPSHOT");
        result.put("timestamp", LocalDateTime.now());
        result.put("llmCircuitBreaker", circuitBreakerInfo());
        return result;
    }

    /**
     * 熔断器当前状态。
     *
     * <p>{@code state} 取值 CLOSED（正常）/ OPEN（熔断中，调用被快速拒绝）/
     * HALF_OPEN（试探恢复中）。{@code failureRate} 在样本不足时为 -1。
     */
    private Map<String, Object> circuitBreakerInfo() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.LLM_BREAKER);
        CircuitBreaker.Metrics metrics = breaker.getMetrics();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", breaker.getName());
        info.put("state", breaker.getState().name());
        info.put("failureRate", metrics.getFailureRate());
        info.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        info.put("failedCalls", metrics.getNumberOfFailedCalls());
        return info;
    }
}
