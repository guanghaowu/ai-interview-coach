package com.aicoach.ai;

import com.aicoach.common.RetryUtil;
import com.aicoach.dto.CritiqueDTO;
import com.aicoach.dto.DimensionPlanDTO;
import com.aicoach.dto.QuestionDTO;
import com.aicoach.dto.QuestionListDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 出题 AgentLoop：Planner → Executor → Critic 两轮闭环
 *
 * <h3>为什么要有这个类</h3>
 * 原来出题是「一次模型调用，直接要 5 道题」。问题有两个：
 * <ol>
 *   <li><b>覆盖不可控</b>：模型很容易几道题挤在同一个技术点上，JD 里的其他技术点没人问；</li>
 *   <li><b>没有校验</b>：题目质量完全依赖单次输出的运气，没人复核。</li>
 * </ol>
 * 改成三角色闭环后，出题变成「先规划 → 再执行 → 后校验 → 不合格就定向修订」，
 * 每个角色的产出都是结构化的，可以被观察、被解释。
 *
 * <h3>为什么最多只跑两轮</h3>
 * 每一轮都是一次真实的模型调用（Token + 10-30 秒延迟）。轮次无上限意味着
 * 延迟与成本都不可控，而收益递减——第一轮修订通常已经解决了主要问题。
 * 用「最多两轮」把最坏情况钉死：最坏 2 次 Executor + 1 次 Planner + 1 次 Critic。
 *
 * <h3>降级策略（关键设计）</h3>
 * <ul>
 *   <li><b>Planner 失败 → 降级继续</b>：传空计划给 Executor，让它自行均衡覆盖，
 *       等价于原来的单次出题。规划是「锦上添花」，不该成为出题的硬依赖。</li>
 *   <li><b>Critic 失败 → 视为通过（fail-open）</b>：审核是质量增强环节，不是正确性依赖。
 *       让 Critic 挂掉导致整条出题链路失败，是拿可用性去换一个非必需的东西。</li>
 *   <li><b>Executor 失败 → 抛出</b>：这是真正的失败，交给 MQ 重试机制处理。</li>
 *   <li><b>修订返回空 → 保留上一版</b>：第二轮改坏了不能把第一轮的成果也丢掉。</li>
 * </ul>
 *
 * <h3>事务边界</h3>
 * 本类<b>不加 @Transactional</b>：内部全是外部 HTTP 调用，长耗时的模型调用绝不能占着
 * 数据库连接（连接池 max-active=20，一次调用 10-30 秒，并发几路就爆）。落库由调用方
 * 在拿到结果后单独做短事务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewAgentLoop {

    /** 轮次硬上限：1 = 首轮通过；2 = 触发一次定向修订 */
    private static final int MAX_ROUNDS = 2;

    /** 每次模型调用的重试次数与首次退避间隔 */
    private static final int RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final InterviewAiService interviewAiService;
    private final ObjectMapper objectMapper;

    /**
     * 执行一次完整的出题闭环。
     *
     * @param jdContent JD 原文
     * @return 题目 + 执行过程信息
     * @throws IllegalStateException Executor 始终拿不到有效题目时抛出（交由 MQ 重试）
     */
    public AgentResult run(String jdContent) {

        // ---------- 第 1 步：Planner 拆解考察维度 ----------
        DimensionPlanDTO plan = null;
        boolean plannerDegraded = false;
        try {
            long t0 = System.currentTimeMillis();
            plan = RetryUtil.retryWithBackoff("Planner拆解维度", RETRY_ATTEMPTS, RETRY_DELAY_MS,
                    () -> interviewAiService.planDimensions(jdContent));
            log.info("Planner 完成: 维度数={}, 耗时={}ms",
                    plan == null || plan.getDimensions() == null ? 0 : plan.getDimensions().size(),
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            plannerDegraded = true;
            log.warn("Planner 失败，降级为「无计划出题」: {}", e.getMessage());
        }
        String planText = toJson(plan);

        // ---------- 第 2 步：Executor 首轮出题 ----------
        QuestionListDTO draft = generate(jdContent, planText, "Executor首轮");

        // ---------- 第 3 步：Critic 校验 + 定向修订闭环 ----------
        CritiqueDTO critique = null;
        boolean criticDegraded = false;
        int rounds = 1;

        while (rounds < MAX_ROUNDS) {
            // lambda 只能捕获 effectively-final 变量，而 draft 会在循环里被替换，
            // 所以每轮先取一份快照交给模型调用。
            final QuestionListDTO draftSnapshot = draft;

            try {
                long t0 = System.currentTimeMillis();
                critique = RetryUtil.retryWithBackoff("Critic审核题目", RETRY_ATTEMPTS, RETRY_DELAY_MS,
                        () -> interviewAiService.critiqueQuestions(jdContent, toJson(draftSnapshot)));
                log.info("Critic 完成: passed={}, 耗时={}ms",
                        critique == null ? null : critique.getPassed(),
                        System.currentTimeMillis() - t0);
            } catch (Exception e) {
                // fail-open：审核失败不该拖垮出题
                criticDegraded = true;
                log.warn("Critic 失败，按通过处理（fail-open）: {}", e.getMessage());
                break;
            }

            if (passed(critique)) {
                break;
            }

            // 不通过 → 带着原题与审核意见定向修订
            log.info("Critic 判定不通过，触发定向修订: reason={}, issues={}, missing={}",
                    critique == null ? null : critique.getReason(),
                    critique == null ? null : critique.getIssues(),
                    critique == null ? null : critique.getMissingDimensions());

            final CritiqueDTO critiqueSnapshot = critique;
            try {
                QuestionListDTO revised = RetryUtil.retryWithBackoff(
                        "Executor定向修订", RETRY_ATTEMPTS, RETRY_DELAY_MS,
                        () -> interviewAiService.reviseQuestions(
                                jdContent, planText, toJson(draftSnapshot), toJson(critiqueSnapshot)));

                if (revised != null && revised.getQuestions() != null && !revised.getQuestions().isEmpty()) {
                    draft = revised;
                    rounds++;
                } else {
                    // 改坏了不能把第一轮的成果也丢掉
                    log.warn("定向修订返回空，保留上一版题目");
                    break;
                }
            } catch (Exception e) {
                log.warn("定向修订失败，保留上一版题目: {}", e.getMessage());
                break;
            }
        }

        List<QuestionDTO> questions = draft.getQuestions();
        log.info("AgentLoop 结束: 轮数={}, 题目数={}, planner降级={}, critic降级={}",
                rounds, questions.size(), plannerDegraded, criticDegraded);

        return new AgentResult(questions, plan, critique, rounds, plannerDegraded, criticDegraded);
    }

    /** 调 Executor 出题，并做「拿不到题目就算失败」的校验 */
    private QuestionListDTO generate(String jdContent, String planText, String stage) {
        QuestionListDTO result = RetryUtil.retryWithBackoff(stage, RETRY_ATTEMPTS, RETRY_DELAY_MS,
                () -> interviewAiService.generateQuestionsByPlan(jdContent, planText));
        if (result == null || result.getQuestions() == null || result.getQuestions().isEmpty()) {
            throw new IllegalStateException("AI 返回题目为空（" + stage + "）");
        }
        return result;
    }

    /**
     * 判定 Critic 是否放行。
     * passed 为 null（模型漏返回该字段）时按通过处理，理由同 fail-open。
     */
    private boolean passed(CritiqueDTO critique) {
        return critique == null || critique.getPassed() == null || Boolean.TRUE.equals(critique.getPassed());
    }

    /** DTO → JSON 字符串，供拼进 prompt。序列化失败时返回空串，不阻断主链路 */
    private String toJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败，降级为空字符串: {}", e.getMessage());
            return "";
        }
    }

    /** 把考察计划渲染成人类可读文本，便于日志排查 */
    public static String describePlan(DimensionPlanDTO plan) {
        if (plan == null || plan.getDimensions() == null || plan.getDimensions().isEmpty()) {
            return "（无考察计划）";
        }
        return plan.getDimensions().stream()
                .map(item -> "%s×%s（%s）".formatted(
                        item.getName(),
                        item.getCount() == null ? "?" : item.getCount(),
                        item.getFocus() == null ? "" : item.getFocus()))
                .collect(Collectors.joining("、"));
    }
}
