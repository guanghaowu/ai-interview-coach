package com.aicoach.ai;

import com.aicoach.config.ResilienceConfig;
import com.aicoach.dto.CritiqueDTO;
import com.aicoach.dto.DimensionPlanDTO;
import com.aicoach.dto.FeedbackDTO;
import com.aicoach.dto.QuestionListDTO;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 带熔断保护的 AI 服务装饰器
 *
 * <h3>为什么用装饰器，而不是在接口方法上加 {@code @CircuitBreaker}</h3>
 * {@code InterviewAiService} 的实现是 LangChain4j 的 {@code AiServices.builder()} 生成的
 * **JDK 动态代理对象**。注解式熔断依赖 Spring AOP 在 Bean 外再包一层代理，
 * 而「动态代理对象」再被 AOP 包装的织入时机与匹配规则都不直观，出问题很难查。
 * 显式装饰器没有这个不确定性：包没包住、包在哪一层，代码里一眼可见。
 *
 * <h3>为什么包在「单次模型调用」这一层</h3>
 * 出题链路里 {@code RetryUtil} 在外层做 3 次指数退避重试，本装饰器包的是<b>每一次</b>模型调用。
 * 也就是说，一次业务失败若三次调用全挂，会计入 3 次熔断失败。
 * 这<b>不是缺陷，而是刻意的</b>：「连续多次调用都失败」正是依赖不可用的强信号，
 * 此时就该尽快打开熔断、别让后续请求继续去撞墙。
 * 反过来若包在最外层，则要 5 次<b>业务</b>失败才打开——按 LLM 单次 6~10 秒算，
 * 等于要等半分钟以上才熔断，太慢。
 *
 * <h3>为什么熔断打开后不在这里降级</h3>
 * 降级策略应该由**知道业务语义的那一层**决定：Planner 挂了可以不要计划继续出题，
 * Critic 挂了可以放行，Executor 挂了就必须失败。这个判断 {@code InterviewAgentLoop}
 * 已经在做（fail-open），装饰器只需如实抛出 {@link CallNotPermittedException}，
 * 不要去替上层决定「什么算可以接受的结果」。
 */
@Slf4j
@Component
@Primary
public class GuardedInterviewAiService implements InterviewAiService {

    private final InterviewAiService delegate;
    private final CircuitBreaker breaker;

    /**
     * @param delegate LangChain4j 生成的原始代理。这里必须用 {@code @Qualifier} 明确指定，
     *                 否则容器里有两个 {@code InterviewAiService}（本类被 {@code @Primary}
     *                 标记为优先），构造器注入会拿到自己，形成循环依赖。
     */
    public GuardedInterviewAiService(@Qualifier("interviewAiService") InterviewAiService delegate,
                                     CircuitBreakerRegistry registry) {
        this.delegate = delegate;
        this.breaker = registry.circuitBreaker(ResilienceConfig.LLM_BREAKER);
    }

    @Override
    public DimensionPlanDTO planDimensions(String jdContent) {
        return guard("planDimensions", () -> delegate.planDimensions(jdContent));
    }

    @Override
    public QuestionListDTO generateQuestionsByPlan(String jdContent, String planText) {
        return guard("generateQuestionsByPlan",
                () -> delegate.generateQuestionsByPlan(jdContent, planText));
    }

    @Override
    public QuestionListDTO reviseQuestions(String jdContent, String planText,
                                           String questionsText, String critiqueText) {
        return guard("reviseQuestions",
                () -> delegate.reviseQuestions(jdContent, planText, questionsText, critiqueText));
    }

    @Override
    public CritiqueDTO critiqueQuestions(String jdContent, String questionsText) {
        return guard("critiqueQuestions",
                () -> delegate.critiqueQuestions(jdContent, questionsText));
    }

    @Override
    public FeedbackDTO evaluateAnswer(String question, String answer, String history) {
        return guard("evaluateAnswer",
                () -> delegate.evaluateAnswer(question, answer, history));
    }

    /**
     * 统一入口：记录成功/失败到熔断器，并在熔断打开时给出可诊断的日志。
     *
     * <p>注意 {@code CallNotPermittedException} 是「被拒绝」而不是「调用失败」，
     * Resilience4j 不会把它计入失败率——否则熔断打开期间的每次拒绝都会继续
     * 把失败率推高，恢复判定就永远做不成了。
     */
    private <T> T guard(String operation, Supplier<T> action) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(breaker, action);
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("LLM 熔断器已打开，快速失败（未发起真实调用）: op={}, state={}",
                    operation, breaker.getState());
            throw e;
        }
    }
}
