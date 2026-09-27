package com.aicoach.ai;

import com.aicoach.dto.CritiqueDTO;
import com.aicoach.dto.DimensionPlanDTO;
import com.aicoach.dto.FeedbackDTO;
import com.aicoach.dto.QuestionListDTO;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 面试 AI 服务（LangChain4j AiServices 动态代理）
 *
 * 由 LangChain4jConfig 中的 AiServices.builder() 生成实现类，
 * 直接注入使用即可，无需手写实现。
 *
 * 出题侧不是单次调用，而是 Planner → Executor → Critic 三角色闭环，
 * 编排逻辑见 {@link InterviewAgentLoop}；本接口只负责「一次模型调用」的原子能力。
 */
public interface InterviewAiService {

    // ==================== Planner ====================

    /**
     * 把 JD 拆成若干考察维度。
     *
     * 这是 AgentLoop 的第一步：先规划再执行，避免模型一口气出题时
     * 几道题全挤在同一个技术点上。
     */
    @SystemMessage("""
            你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
            你的任务是阅读一份 JD（职位描述），把它拆解成 3-5 个考察维度。

            要求：
            1. 维度必须来自 JD 中真实出现的核心技术点，不要凭空添加 JD 没提的技术
            2. 维度之间不要重叠，例如「Redis 缓存」与「Redis 持久化」应合并成一个维度
            3. 维度名要短（不超过 12 个字），如「并发与线程安全」「MySQL 索引优化」
            4. focus 用一句话说明这个维度具体考察什么，供后续出题参考
            5. 所有维度的 count 之和为 5
            6. 只返回 JSON，不要任何解释文字
            """)
    @UserMessage("请拆解以下 JD 的考察维度：\n\n{{jd}}")
    DimensionPlanDTO planDimensions(@V("jd") String jdContent);

    // ==================== Executor ====================

    /**
     * 按考察计划生成题目。
     *
     * plan 为空时（Planner 降级）会要求模型自行均衡覆盖，等价于原来的单次出题。
     */
    @SystemMessage("""
            你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
            你的任务是根据 JD 与给定的考察计划，生成 5 道高质量面试题。

            要求：
            1. 题目必须紧扣 JD 中出现的核心技术栈，不要偏离
            2. 每道题的 dimension 字段必须填写它所属的考察维度名（取自考察计划）
            3. 题型混合，type 取值：1=编程题 2=场景题 3=项目题 4=八股题
            4. 难度分布：至少一道中等(difficulty=2)，可含一道难题(difficulty=3)
            5. 每道题要具体、可作答，避免"请介绍下 Java"这类空泛问题
            6. 只返回 JSON，不要任何解释文字

            【最重要的一条】维度一致性：
            每道题的 content 必须真正属于它标注的 dimension，二者不能张冠李戴。
            反面例子：把一道「两数之和」这类通用算法题标注为「Redis 缓存应用」。
            如果某个维度你一时想不到好题，就出该维度下更基础的题，
            也不要把无关题目挂到它下面充数。
            """)
    @UserMessage("""
            JD：
            {{jd}}

            考察计划：
            {{plan}}

            请按考察计划出题。若考察计划为空，请自行均衡覆盖 JD 中的技术栈。
            """)
    QuestionListDTO generateQuestionsByPlan(@V("jd") String jdContent,
                                           @V("plan") String planText);

    /**
     * 按 Critic 的审核意见定向修订题目。
     *
     * 注意这是「修订」不是「重生成」：带上原题与具体问题，让模型只改有问题的地方，
     * 既省 Token，也避免第二轮把原本合格的题目改坏。
     */
    @SystemMessage("""
            你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
            你收到了一批初稿面试题以及审核意见，需要按审核意见定向修订。

            要求：
            1. 只修改审核意见指出的问题，没有问题的题目尽量保持原样
            2. 审核意见指出缺失的维度，必须补上对应的新题目
            3. 保持题目总数为 5 道，dimension 字段仍须填写
            4. 返回修订后的完整题目列表（不是增量）
            5. 只返回 JSON，不要任何解释文字
            """)
    @UserMessage("""
            JD：
            {{jd}}

            考察计划：
            {{plan}}

            初稿题目：
            {{questions}}

            审核意见：
            {{critique}}

            请输出修订后的完整题目列表。
            """)
    QuestionListDTO reviseQuestions(@V("jd") String jdContent,
                                    @V("plan") String planText,
                                    @V("questions") String questionsText,
                                    @V("critique") String critiqueText);

    // ==================== Critic ====================

    /**
     * 审核一批题目的质量。
     *
     * 这是 AgentLoop 的闭环校验环节：不通过时会把 issues / missingDimensions
     * 交给 Executor 做定向修订，最多再跑一轮。
     */
    @SystemMessage("""
            你是一位严格的面试题质量审核员。
            你的任务是审核一批面试题是否合格，而不是重新出题。

            审核标准：
            1. **维度一致性（最优先，必须严查）**：每道题的 content 是否真正属于它标注的
               dimension。张冠李戴必须判不通过——例如把「两数之和」这类通用算法题
               标注为「Redis 缓存应用」，就是典型的不通过项。
            2. 覆盖度：JD 中的核心技术点是否都被覆盖到
            3. 去重：是否存在两道题在考察同一个技术点
            4. 具体性：是否存在空泛到无法作答的题目
            5. 难度分布：是否至少有中等及以上难度的题

            判断尺度：除维度一致性必须严查外，其余方面宁可放过、不要苛刻。
            例如题目略简单、表述稍啰嗦，都不应判不通过；只有实质缺陷才判不通过。
            这样既拦住真问题，又避免为了追求完美而反复重出、浪费成本。

            要求：
            1. passed 为 true 表示通过，false 表示需要修订
            2. issues 逐条列出具体问题，要指出是第几题，便于定向修订
            3. missingDimensions 列出 JD 提到但没被覆盖的维度
            4. 只返回 JSON，不要任何解释文字
            """)
    @UserMessage("""
            JD：
            {{jd}}

            待审核题目：
            {{questions}}

            请给出审核结论。
            """)
    CritiqueDTO critiqueQuestions(@V("jd") String jdContent,
                                  @V("questions") String questionsText);

    // ==================== 评分（保持单次调用）====================

    /**
     * 对用户的面试回答评分并给出反馈
     *
     * 模型可通过 InterviewTools 调用工具获取会话上下文（Function Calling）。
     */
    @SystemMessage("""
            你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
            你需要对候选人的回答进行客观、专业的评分。

            评分标准（1-10 分）：
            - 9-10：回答准确完整、有深度，能举一反三
            - 7-8：回答基本正确，覆盖要点，但深度一般
            - 5-6：回答方向正确，但有明显遗漏或理解偏差
            - 3-4：回答不完整或存在错误
            - 1-2：答非所问或基本错误

            要求：
            1. pros 写具体的优点，不要空话套话
            2. cons 写具体问题，指出缺了什么
            3. suggestions 给可操作的改进建议
            4. 只返回 JSON，不要任何解释文字
            """)
    @UserMessage("""
            请对以下面试回答评分并给出反馈。

            题目：{{question}}

            候选人回答：{{answer}}

            本轮之前的对话历史（用于判断是否为追问、以及前后回答的一致性）：
            {{history}}
            """)
    FeedbackDTO evaluateAnswer(@V("question") String question,
                               @V("answer") String answer,
                               @V("history") String history);
}
