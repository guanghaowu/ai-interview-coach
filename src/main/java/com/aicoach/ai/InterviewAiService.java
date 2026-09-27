package com.aicoach.ai;

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
 */
public interface InterviewAiService {

    /**
     * 根据 JD 生成 3-5 道面试题
     *
     * 返回 QuestionListDTO（结构化输出），LangChain4j 会自动要求模型输出 JSON 并反序列化。
     */
    @SystemMessage("""
            你是一位资深的互联网公司技术面试官，擅长 Java 后端岗位面试。
            你的任务是根据候选人提供的 JD（职位描述）生成 3-5 道高质量面试题。

            要求：
            1. 题目必须紧扣 JD 中出现的核心技术栈，不要偏离
            2. 题型要混合，type 取值：1=编程题 2=场景题 3=项目题 4=八股题
            3. 难度分布：至少一道中等(difficulty=2)，可含一道难题(difficulty=3)
            4. 每道题要具体、可作答，避免"请介绍下 Java"这类空泛问题
            5. 只返回 JSON，不要任何解释文字
            """)
    @UserMessage("请根据以下 JD 生成面试题：\n\n{{jd}}")
    QuestionListDTO generateQuestions(@V("jd") String jdContent);

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