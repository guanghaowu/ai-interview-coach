package com.aicoach.ai;

import com.aicoach.entity.Question;
import com.aicoach.mapper.QuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * AI 可调用的工具（Function Calling）
 *
 * 模型在评分/出题过程中会自主决定是否调用这些方法，
 * 从而拿到数据库里的真实数据，而不是凭空编造。
 */
@Slf4j
@RequiredArgsConstructor
public class InterviewTools {

    private final QuestionMapper questionMapper;

    /**
     * 查询指定会话的全部题目（供模型评分时了解上下文）
     */
    @Tool("查询指定会话中的所有题目内容，用于评分时了解上下文，避免重复或偏离")
    public List<String> listSessionQuestions(@P("会话 ID") Long sessionId) {
        log.debug("AI 调用工具 listSessionQuestions, sessionId={}", sessionId);
        return questionMapper.selectList(
                        new LambdaQueryWrapper<Question>()
                                .eq(Question::getSessionId, sessionId)
                                .orderByAsc(Question::getSortOrder))
                .stream()
                .map(Question::getContent)
                .toList();
    }

    /**
     * 查询某道题的题目内容（供模型拿到原始题干）
     */
    @Tool("根据题目 ID 查询题目的原始内容")
    public String getQuestionContent(@P("题目 ID") Long questionId) {
        log.debug("AI 调用工具 getQuestionContent, questionId={}", questionId);
        Question q = questionMapper.selectById(questionId);
        return q == null ? "题目不存在" : q.getContent();
    }
}