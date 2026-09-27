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
     * 落库 AI 生成的题目。
     *
     * 这里顺手做了两件防御：
     * 1. 题型/难度归一化——大模型结构化输出可能返回 null 或越界值，不能让脏数据进库；
     * 2. 序号由服务端统一编号，不信任模型返回的顺序。
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
            entity.setSortOrder(order++);
            questionMapper.insert(entity);
        }
        return items.size();
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
            copy.setSortOrder(src.getSortOrder());
            questionMapper.insert(copy);
            copies.add(copy);
        }
        return copies;
    }
}
