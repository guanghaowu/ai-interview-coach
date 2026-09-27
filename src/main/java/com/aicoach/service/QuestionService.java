package com.aicoach.service;

import com.aicoach.constant.Difficulty;
import com.aicoach.constant.QuestionType;
import com.aicoach.dto.QuestionDTO;
import com.aicoach.entity.Question;
import com.aicoach.mapper.QuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 题目读写服务
 *
 * 为什么抽出来：「查题目 / 统计题目数 / 复制题目 / 落库 AI 生成的题目」
 * 这四个动作原本在 InterviewServiceImpl 与 InterviewConsumer 里各写了一遍。
 * 复制逻辑一旦要加字段（比如以后加 tags），就得改两处，非常容易漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionMapper questionMapper;

    /** 按序号查询会话的全部题目 */
    public List<Question> listBySession(Long sessionId) {
        return questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getSessionId, sessionId)
                        .orderByAsc(Question::getSortOrder));
    }

    /** 按 ID 查询单道题目 */
    public Question getById(Long id) {
        return questionMapper.selectById(id);
    }

    /** 统计会话已有题目数（幂等防重用） */
    public long countBySession(Long sessionId) {
        Long count = questionMapper.selectCount(
                new LambdaQueryWrapper<Question>().eq(Question::getSessionId, sessionId));
        return count == null ? 0L : count;
    }

    /**
     * 一次查询拿到多个会话的题目数。
     *
     * 列表页最多 20 条会话、每条最多几道题，用「一次 IN 查询 + 内存分组」
     * 把 N 次 count 压成 1 次，避免列表接口的 N+1 查询。
     */
    public Map<Long, Long> countBySessions(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        List<Question> list = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().in(Question::getSessionId, sessionIds));
        return list.stream()
                .collect(Collectors.groupingBy(Question::getSessionId, Collectors.counting()));
    }

    /**
     * 落库 AI 生成的题目。
     *
     * 这里顺手做了三件防御：
     * 1. 题型/难度归一化——大模型结构化输出可能返回 null 或越界值，不能让脏数据进库；
     * 2. 序号由服务端统一编号，不信任模型返回的顺序；
     * 3. dimension 长度截断——模型偶尔会把一句话当维度名，超长会撑爆列宽。
     *
     * @return 落库条数
     */
    public int saveGenerated(Long sessionId, List<QuestionDTO> items) {
        int order = 1;
        for (QuestionDTO dto : items) {
            Question entity = new Question();
            entity.setSessionId(sessionId);
            entity.setType(QuestionType.normalize(dto.getType()));
            entity.setContent(dto.getContent());
            entity.setDifficulty(Difficulty.normalize(dto.getDifficulty()));
            entity.setDimension(truncateDimension(dto.getDimension()));
            entity.setSortOrder(order++);
            questionMapper.insert(entity);
        }
        return items.size();
    }

    /** 维度名截断到列宽（VARCHAR(50)）以内，避免超长导致插入失败 */
    private String truncateDimension(String dimension) {
        if (dimension == null) {
            return null;
        }
        String trimmed = dimension.trim();
        return trimmed.length() <= 50 ? trimmed : trimmed.substring(0, 50);
    }

    /**
     * 把一个会话的题目整份复制到另一个会话。
     * 用于「同一份 JD 复用已有题目」场景，避免重复调用 AI 烧 Token。
     *
     * @return 复制后的题目列表
     */
    public List<Question> copyTo(Long sourceSessionId, Long targetSessionId) {
        List<Question> sources = listBySession(sourceSessionId);
        List<Question> copies = new ArrayList<>(sources.size());
        for (Question src : sources) {
            Question copy = new Question();
            copy.setSessionId(targetSessionId);
            copy.setType(src.getType());
            copy.setContent(src.getContent());
            copy.setDifficulty(src.getDifficulty());
            copy.setDimension(src.getDimension());
            copy.setSortOrder(src.getSortOrder());
            questionMapper.insert(copy);
            copies.add(copy);
        }
        return copies;
    }
}
