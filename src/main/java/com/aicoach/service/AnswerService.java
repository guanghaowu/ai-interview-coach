package com.aicoach.service;

import com.aicoach.constant.AnswerStatus;
import com.aicoach.entity.Answer;
import com.aicoach.entity.Feedback;
import com.aicoach.mapper.AnswerMapper;
import com.aicoach.mapper.FeedbackMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 回答与评分反馈的查询服务
 *
 * 读多写少：写入由 {@link FeedbackService} 在短事务里完成，这里只负责查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerService {

    private final AnswerMapper answerMapper;
    private final FeedbackMapper feedbackMapper;

    /** 查询会话的全部回答，按 id 倒序（便于「取每题最新一次」） */
    public List<Answer> listBySession(Long sessionId) {
        return answerMapper.selectList(
                new LambdaQueryWrapper<Answer>()
                        .eq(Answer::getSessionId, sessionId)
                        .orderByDesc(Answer::getId));
    }

    /**
     * 一次查询拿到多个会话的「已评分回答数」。
     *
     * 刻意用「一次 IN 查询 + 内存分组」而不是逐个会话 count：
     * 列表页最多 20 条，每条最多几道题，数据量完全可控，
     * 却能把 N 次查询压成 1 次（N+1 是列表接口最常见的性能坑）。
     */
    public Map<Long, Long> countGradedBySessions(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        List<Answer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<Answer>()
                        .in(Answer::getSessionId, sessionIds)
                        .eq(Answer::getStatus, AnswerStatus.GRADED.getCode()));
        return answers.stream()
                .collect(Collectors.groupingBy(Answer::getSessionId, Collectors.counting()));
    }

    /** 批量取评分反馈，避免逐条查询 */
    public Map<Long, Feedback> mapByIds(Collection<Long> feedbackIds) {
        if (feedbackIds == null || feedbackIds.isEmpty()) {
            return Map.of();
        }
        List<Feedback> list = feedbackMapper.selectBatchIds(feedbackIds);
        Map<Long, Feedback> map = new HashMap<>(list.size());
        for (Feedback f : list) {
            map.put(f.getId(), f);
        }
        return map;
    }
}
