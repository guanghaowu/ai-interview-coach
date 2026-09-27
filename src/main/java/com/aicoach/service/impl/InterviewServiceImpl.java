package com.aicoach.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.aicoach.ai.InterviewAiService;
import com.aicoach.common.BusinessException;
import com.aicoach.common.ThreadLocalUtil;
import com.aicoach.dto.AnswerResultVO;
import com.aicoach.dto.CreateSessionDTO;
import com.aicoach.dto.FeedbackDTO;
import com.aicoach.dto.QuestionVO;
import com.aicoach.dto.SessionVO;
import com.aicoach.dto.SubmitAnswerDTO;
import com.aicoach.entity.Answer;
import com.aicoach.entity.Feedback;
import com.aicoach.entity.InterviewSession;
import com.aicoach.entity.Question;
import com.aicoach.mapper.AnswerMapper;
import com.aicoach.mapper.FeedbackMapper;
import com.aicoach.mapper.InterviewSessionMapper;
import com.aicoach.mapper.QuestionMapper;
import com.aicoach.mq.AnswerMessage;
import com.aicoach.mq.InterviewMessage;
import com.aicoach.mq.InterviewProducer;
import com.aicoach.service.InterviewService;
import com.aicoach.service.SessionMemoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟面试服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    private final InterviewAiService interviewAiService;
    private final InterviewSessionMapper sessionMapper;
    private final QuestionMapper questionMapper;
    private final AnswerMapper answerMapper;
    private final FeedbackMapper feedbackMapper;
    private final SessionMemoryService sessionMemoryService;
    private final InterviewProducer interviewProducer;

    @Override
    public SessionVO createSession(CreateSessionDTO dto) {
        Long userId = ThreadLocalUtil.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        String jdMd5 = DigestUtil.md5Hex(dto.getJdContent());

        // 1. 命中缓存：已有相同 JD 的已完成会话 → 复用题目，不再调 AI（省 Token）
        // 注意：必须带 userId 过滤，否则会复用其他用户的题目（数据越界）
        InterviewSession cached = sessionMapper.selectOne(
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getUserId, userId)
                        .eq(InterviewSession::getJdMd5, jdMd5)
                        .eq(InterviewSession::getStatus, 1)
                        .orderByDesc(InterviewSession::getId)
                        .last("LIMIT 1"));
        if (cached != null) {
            List<Question> cachedQuestions = questionMapper.selectList(
                    new LambdaQueryWrapper<Question>()
                            .eq(Question::getSessionId, cached.getId())
                            .orderByAsc(Question::getSortOrder));
            if (!cachedQuestions.isEmpty()) {
                return reuseQuestions(userId, dto, jdMd5, cachedQuestions);
            }
        }

        // 2. 未命中：先创建会话（status=0 表示「AI 出题中」），主接口不阻塞
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setJdMd5(jdMd5);
        session.setJdContent(dto.getJdContent());
        session.setPosition("解析中");
        session.setTechStack("解析中");
        session.setStatus(0);
        sessionMapper.insert(session);

        // 2. 投递 MQ 异步任务，AI 出题在 Consumer 侧执行
        interviewProducer.sendGenerateTask(
                new InterviewMessage(session.getId(), userId, dto.getJdContent(), jdMd5));

        log.info("会话创建成功（AI 异步出题中）: sessionId={}, userId={}", session.getId(), userId);

        // 3. 立即返回；前端轮询 GET /api/interview/{sessionId} 取结果
        return buildSessionVO(session, List.of());
    }

    @Override
    public SessionVO getSession(Long sessionId) {
        Long userId = ThreadLocalUtil.get();
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该会话");
        }

        List<Question> questions = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getSessionId, sessionId)
                        .orderByAsc(Question::getSortOrder)
        );
        return buildSessionVO(session, questions);
    }

    @Override
    public AnswerResultVO submitAnswer(SubmitAnswerDTO dto) {
        Long userId = ThreadLocalUtil.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        // 1. 查题目
        Question question = questionMapper.selectById(dto.getQuestionId());
        if (question == null) {
            throw new BusinessException("题目不存在");
        }

        // 2. 查会话并校验归属
        InterviewSession session = sessionMapper.selectById(question.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该题目");
        }

        // 3. 写入会话记忆（用户本轮回答）
        sessionMemoryService.append(session.getId(), "user", dto.getContent());

        // 4. 落库回答（status=0 待评分）
        Answer answer = new Answer();
        answer.setQuestionId(dto.getQuestionId());
        answer.setSessionId(session.getId());
        answer.setUserId(userId);
        answer.setContent(dto.getContent());
        answer.setRound(1);
        answer.setStatus(0);
        answerMapper.insert(answer);

        // 5. 投递 MQ，AI 评分交给消费端执行。
        //    不能同步调：AI 评分耗时 5-15 秒，会长时间占用 HTTP 线程，
        //    并发上来会耗尽 Tomcat 线程池，连累登录、查题等所有接口。
        interviewProducer.sendAnswerTask(new AnswerMessage(
                answer.getId(), dto.getQuestionId(), session.getId(), userId,
                question.getContent(), dto.getContent()));

        log.info("回答已提交（AI 异步评分中）: answerId={}", answer.getId());

        // 6. 立即返回，前端轮询 GET /api/interview/answer/{answerId} 取结果
        AnswerResultVO vo = new AnswerResultVO();
        vo.setAnswerId(answer.getId());
        vo.setStatus(0);
        return vo;
    }

    @Override
    public AnswerResultVO getAnswerResult(Long answerId) {
        Long userId = ThreadLocalUtil.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        Answer answer = answerMapper.selectById(answerId);
        if (answer == null) {
            throw new BusinessException("回答不存在");
        }
        if (!answer.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该回答");
        }

        AnswerResultVO vo = new AnswerResultVO();
        vo.setAnswerId(answer.getId());
        vo.setStatus(answer.getStatus());
        vo.setScore(answer.getScore());

        if (answer.getFeedbackId() != null) {
            Feedback feedback = feedbackMapper.selectById(answer.getFeedbackId());
            if (feedback != null) {
                vo.setPros(feedback.getPros());
                vo.setCons(feedback.getCons());
                vo.setSuggestions(feedback.getSuggestions());
            }
        }
        return vo;
    }

    /**
     * 复用已有题目（相同 JD 命中缓存时调用）
     */
    private SessionVO reuseQuestions(Long userId, CreateSessionDTO dto, String jdMd5,
                                     List<Question> cachedQuestions) {
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setJdMd5(jdMd5);
        session.setJdContent(dto.getJdContent());
        session.setPosition("复用缓存");
        session.setTechStack("复用缓存");
        session.setStatus(1);
        sessionMapper.insert(session);

        List<Question> copies = new ArrayList<>();
        for (Question src : cachedQuestions) {
            Question copy = new Question();
            copy.setSessionId(session.getId());
            copy.setType(src.getType());
            copy.setContent(src.getContent());
            copy.setDifficulty(src.getDifficulty());
            copy.setSortOrder(src.getSortOrder());
            questionMapper.insert(copy);
            copies.add(copy);
        }

        log.info("命中 JD 缓存，复用题目: sessionId={}, 复用 {} 道题", session.getId(), copies.size());
        return buildSessionVO(session, copies);
    }

    private SessionVO buildSessionVO(InterviewSession session, List<Question> questions) {
        SessionVO vo = new SessionVO();
        vo.setSessionId(session.getId());
        vo.setPosition(session.getPosition());
        vo.setTechStack(session.getTechStack());
        vo.setStatus(session.getStatus());
        vo.setCreatedAt(session.getCreatedAt());

        List<QuestionVO> qvos = new ArrayList<>();
        for (Question q : questions) {
            qvos.add(BeanUtil.copyProperties(q, QuestionVO.class));
        }
        vo.setQuestions(qvos);
        return vo;
    }
}