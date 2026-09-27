package com.aicoach.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 熔断器配置（保护 LLM 调用）
 *
 * <h3>为什么需要熔断</h3>
 * 在此之前，DeepSeek 侧持续故障时的行为是：**每个请求都真实发起调用、等到 60 秒超时**。
 * 后果有两层：
 * <ul>
 *   <li><b>资源层</b>：MQ 消费线程被长时间占住，消费积压，整个异步链路被拖慢；</li>
 *   <li><b>体验层</b>：明知依赖已经挂了，还要让每个用户各等 60 秒才失败。</li>
 * </ul>
 * 熔断的核心价值不是「让失败变少」（失败该失败），而是
 * <b>让失败变快</b>——依赖确定不可用时立刻拒绝，把等待时间从 60 秒降到毫秒级。
 *
 * <h3>参数为什么这么定</h3>
 * <table border="1">
 *   <tr><th>参数</th><th>值</th><th>理由</th></tr>
 *   <tr><td>滑动窗口</td><td>计数式，10 次</td>
 *       <td>LLM 调用单次 6~10 秒，10 次样本已经跨越一两分钟，时间窗口反而过慢</td></tr>
 *   <tr><td>最小调用数</td><td>5</td>
 *       <td>不做这个下限的话，冷启动时「第一次调用就失败」会直接把失败率算成 100% 而熔断</td></tr>
 *   <tr><td>失败率阈值</td><td>50%</td>
 *       <td>LLM 本身有随机性（偶发超时、偶发返回不可解析），阈值定太低会被抖动误伤</td></tr>
 *   <tr><td>打开时长</td><td>30 秒</td>
 *       <td>足够让上游抖动恢复；太短会反复试探，太长则恢复慢</td></tr>
 *   <tr><td>半开放行数</td><td>3</td>
 *       <td>放 1 个太保守（单次偶发失败又被打回打开态），3 个能较快确认恢复</td></tr>
 * </table>
 *
 * <h3>刻意不做的：慢调用熔断</h3>
 * Resilience4j 支持「调用超过 N 秒就算慢失败」。但 LLM 正常就要 6~10 秒，
 * 设阈值等于给正常调用判死刑；不设则形同虚设。所以这里只做失败率熔断。
 *
 * <h3>熔断打开后的行为</h3>
 * 调用会立刻抛出 {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}，
 * 由上层决定降级：出题链路的 Planner / Critic 本就是 fail-open（见 {@code InterviewAgentLoop}），
 * 只有 Executor 是硬依赖，它会失败 → 交给 MQ 重试 → 重试耗尽进死信 → 落 {@code failed_task}
 * → 定时任务在熔断恢复后重投。
 */
@Configuration
public class ResilienceConfig {

    /** LLM 调用熔断器名称（全局共用一个：所有出题/评分调用都打同一个上游） */
    public static final String LLM_BREAKER = "llm";

    /** 滑动窗口内至少要有这么多次调用，才开始统计失败率 */
    private static final int MIN_CALLS = 5;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                // 打开时长到期后自动转半开，不必等下一次调用触发
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        return CircuitBreakerRegistry.of(config);
    }
}
