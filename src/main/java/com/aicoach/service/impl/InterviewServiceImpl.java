package com.aicoach.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aicoach.common.BusinessException;
import com.aicoach.common.ThreadLocalUtil;
import com.aicoach.constant.AnswerStatus;
import com.aicoach.constant.SessionStatus;
import com.aicoach.dto.AnswerResultVO;
import com.aicoach.dto.CreateSessionDTO;
import com.aicoach.dto.PageResultVO;
import com.aicoach.dto.QuestionAnswerVO;
import com.aicoach.dto.QuestionVO;
import com.aicoach.dto.SessionDetailVO;
import com.aicoach.dto.SessionListItemVO;
import com.aicoach.dto.SessionVO;
import com.aicoach.dto.SubmitAnswerDTO;
import com.aicoach.entity.Answer;
import com.aicoach.entity.Feedback;
import com.aicoach.entity.InterviewSession;
import com.aicoach.entity.Question;
import com.aicoach.mapper.AnswerMapper;
import com.aicoach.mapper.FeedbackMapper;
import com.aicoach.mapper.InterviewSessionMapper;
import com.aicoach.mq.AnswerMessage;
import com.aicoach.mq.InterviewMessage;
import com.aicoach.mq.InterviewProducer;
import com.aicoach.service.AnswerService;
import com.aicoach.service.InterviewService;
import com.aicoach.service.QuestionService;
import com.aicoach.service.SessionMemoryService;
import com.aicoach.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模拟面试服务实现
 *
 * 事务边界说明：本类**刻意不加 @Transactional**。
 * 所有需要事务的写操作都收在 {@link SessionService} / {@link com.aicoach.service.FeedbackService}
 * 这些独立 Bean 里，避免 private 方法自调用导致 @Transactional 静默失效。
 * 本类只负责编排：读数据 → 调 MQ → 拼 VO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    private final InterviewSessionMapper sessionMapper;
    private final AnswerMapper answerMapper;
    private final FeedbackMapper feedbackMapper;
    private final QuestionService questionService;
    private final AnswerService answerService;
    private final SessionService sessionService;
    private final SessionMemoryService sessionMemoryService;
    private final InterviewProducer interviewProducer;

    @Override
    public SessionVO createSession(CreateSessionDTO dto) {
        Long userId = requireLogin();
        String jdMd5 = DigestUtil.md5Hex(dto.getJdContent());

        // 1. 命中缓存：本人已有相同 JD 的已完成会话 → 复用题目，不再调 AI（省 Token）
        //    必须带 userId 过滤，否则会复用其他用户的题目（数据越界）
        InterviewSession cached = sessionMapper.selectOne(
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getUserId, userId)
                        .eq(InterviewSession::getJdMd5, jdMd5)
                        .eq(InterviewSession::getStatus, SessionStatus.DONE.getCode())
                        .orderByDesc(InterviewSession::getId)
                        .last("LIMIT 1"));
        if (cached != null && questionService.countBySession(cached.getId()) > 0) {
            InterviewSession session = sessionService.reuseFromHistory(
                    userId, jdMd5, dto.getJdContent(), cached.getId());
            return buildSessionVO(session, questionService.listBySession(session.getId()));
        }

        // 2. 新建会话（独立事务，方法返回时已提交）
        InterviewSession session = sessionService.createPending(userId, jdMd5, dto.getJdContent());

        // 3. 事务提交之后再投递 MQ
        dispatchGenerateTask(session, userId, jdMd5, dto.getJdContent());

        // 4. 立即返回；前端轮询 GET /api/interview/{sessionId} 取结果
        return buildSessionVO(session, List.of());
    }

    @Override
    public SessionVO getSession(Long sessionId) {
        Long userId = requireLogin();
        InterviewSession session = requireOwnedSession(sessionId, userId);
        return buildSessionVO(session, questionService.listBySession(sessionId));
    }

    @Override
    public PageResultVO<SessionListItemVO> listSessions(int page, int size) {
        Long userId = requireLogin();

        // 页码与页大小做兜底，防止 -1 / 100000 这类入参把库拖垮
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        Page<InterviewSession> result = sessionMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getUserId, userId)
                        .orderByDesc(InterviewSession::getId));

        List<InterviewSession> records = result.getRecords();
        List<Long> sessionIds = records.stream().map(InterviewSession::getId).toList();

        // 批量取计数：2 次查询搞定整页，而不是每条会话查两次（N+1）
        Map<Long, Long> questionCounts = questionService.countBySessions(sessionIds);
        Map<Long, Long> gradedCounts = answerService.countGradedBySessions(sessionIds);

        List<SessionListItemVO> items = new ArrayList<>(records.size());
        for (InterviewSession s : records) {
            SessionListItemVO vo = new SessionListItemVO();
            vo.setSessionId(s.getId());
            vo.setPosition(s.getPosition());
            vo.setTechStack(s.getTechStack());
            vo.setStatus(s.getStatus());
            vo.setCreatedAt(s.getCreatedAt());
            vo.setQuestionCount(questionCounts.getOrDefault(s.getId(), 0L));
            vo.setAnsweredCount(gradedCounts.getOrDefault(s.getId(), 0L));
            items.add(vo);
        }
        return PageResultVO.of(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    @Override
    public SessionDetailVO getSessionDetail(Long sessionId) {
        Long userId = requireLogin();
        InterviewSession session = requireOwnedSession(sessionId, userId);

        List<Question> questions = questionService.listBySession(sessionId);
        List<Answer> answers = answerService.listBySession(sessionId);

        // 每题只保留最新一次回答。answers 已按 id 倒序，首次出现即最新
        Map<Long, Answer> latestAnswerByQuestion = new LinkedHashMap<>();
        for (Answer a : answers) {
            latestAnswerByQuestion.putIfAbsent(a.getQuestionId(), a);
        }

        // 批量取反馈，避免逐条查询
        Set<Long> feedbackIds = latestAnswerByQuestion.values().stream()
                .map(Answer::getFeedbackId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Feedback> feedbackMap = answerService.mapByIds(feedbackIds);

        List<QuestionAnswerVO> items = new ArrayList<>(questions.size());
        long answered = 0;
        for (Question q : questions) {
            QuestionAnswerVO vo = new QuestionAnswerVO();
            vo.setQuestionId(q.getId());
            vo.setType(q.getType());
            vo.setContent(q.getContent());
            vo.setDifficulty(q.getDifficulty());
            vo.setDimension(q.getDimension());
            vo.setSortOrder(q.getSortOrder());

            Answer a = latestAnswerByQuestion.get(q.getId());
            if (a != null) {
                vo.setAnswerId(a.getId());
                vo.setAnswerContent(a.getContent());
                vo.setAnswerStatus(a.getStatus());
                vo.setScore(a.getScore());
                if (AnswerStatus.GRADED.matches(a.getStatus())) {
                    answered++;
                }
                Feedback fb = a.getFeedbackId() == null ? null : feedbackMap.get(a.getFeedbackId());
                if (fb != null) {
                    vo.setPros(fb.getPros());
                    vo.setCons(fb.getCons());
                    vo.setSuggestions(fb.getSuggestions());
                }
            }
            items.add(vo);
        }

        SessionDetailVO detail = new SessionDetailVO();
        detail.setSessionId(session.getId());
        detail.setPosition(session.getPosition());
        detail.setTechStack(session.getTechStack());
        detail.setStatus(session.getStatus());
        detail.setAgentRounds(session.getAgentRounds());
        detail.setCreatedAt(session.getCreatedAt());
        detail.setQuestionCount(questions.size());
        detail.setAnsweredCount(answered);
        detail.setItems(items);
        return detail;
    }

    @Override
    public AnswerResultVO submitAnswer(SubmitAnswerDTO dto) {
        Long userId = requireLogin();

        // 1. 查题目
        Question question = questionService.getById(dto.getQuestionId());
        if (question == null) {
            throw new BusinessException(404, "题目不存在");
        }

        // 2. 查会话并校验归属
        InterviewSession session = requireOwnedSession(question.getSessionId(), userId);

        // 3. 出题尚未完成 / 已失败时不允许作答，避免把回答挂到一道不存在的题上
        if (!SessionStatus.DONE.matches(session.getStatus())) {
            throw new BusinessException(400, "会话尚未出题完成，请稍后重试");
        }

        // 4. 写入会话记忆（用户本轮回答）
        sessionMemoryService.append(session.getId(), "user", dto.getContent());

        // 5. 落库回答（status=0 待评分）。此处无事务，insert 立即提交，
        //    因此下面投递 MQ 时数据一定已可见。
        Answer answer = new Answer();
        answer.setQuestionId(dto.getQuestionId());
        answer.setSessionId(session.getId());
        answer.setUserId(userId);
        answer.setContent(dto.getContent());
        answer.setRound(1);
        answer.setStatus(AnswerStatus.PENDING.getCode());
        answerMapper.insert(answer);

        // 6. 投递 MQ，AI 评分交给消费端执行。
        //    不能同步调：AI 评分耗时 5-15 秒，会长时间占用 HTTP 线程，
        //    并发上来会耗尽 Tomcat 线程池，连累登录、查题等所有接口。
        interviewProducer.sendAnswerTask(new AnswerMessage(
                answer.getId(), dto.getQuestionId(), session.getId(), userId,
                question.getContent(), dto.getContent()));

        log.info("回答已提交（AI 异步评分中）: answerId={}", answer.getId());

        // 7. 立即返回，前端轮询 GET /api/interview/answer/{answerId} 取结果
        AnswerResultVO vo = new AnswerResultVO();
        vo.setAnswerId(answer.getId());
        vo.setStatus(AnswerStatus.PENDING.getCode());
        return vo;
    }

    @Override
    public AnswerResultVO getAnswerResult(Long answerId) {
        Long userId = requireLogin();

        Answer answer = answerMapper.selectById(answerId);
        if (answer == null) {
            throw new BusinessException(404, "回答不存在");
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
     * 投递出题任务。
     *
     * **必须在会话事务提交之后调用**：若在事务内投递，消息可能先于事务提交被消费，
     * 消费端按 READ_COMMITTED 读不到会话行，于是题目写进去了、会话却永远停在「出题中」。
     *
     * 投递失败时事务已提交、无法回滚，因此走补偿：把会话置为失败终态，
     * 让前端拿到明确结果而不是无限轮询。
     */
    private void dispatchGenerateTask(InterviewSession session, Long userId, String jdMd5, String jdContent) {
        try {
            interviewProducer.sendGenerateTask(new InterviewMessage(session.getId(), userId, jdContent, jdMd5));
        } catch (RuntimeException e) {
            log.error("MQ 投递失败，补偿标记会话为失败: sessionId={}", session.getId(), e);
            sessionService.updateStatus(session.getId(), SessionStatus.FAILED);
            throw e;
        }
    }

    /** 取当前登录用户，未登录直接 401 */
    private Long requireLogin() {
        Long userId = ThreadLocalUtil.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }

    /** 取会话并校验归属，不存在 404、非本人 403 */
    private InterviewSession requireOwnedSession(Long sessionId, Long userId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该会话");
        }
        return session;
    }

    private SessionVO buildSessionVO(InterviewSession session, List<Question> questions) {
        SessionVO vo = new SessionVO();
        vo.setSessionId(session.getId());
        vo.setPosition(session.getPosition());
        vo.setTechStack(session.getTechStack());
        vo.setStatus(session.getStatus());
        vo.setAgentRounds(session.getAgentRounds());
        vo.setCreatedAt(session.getCreatedAt());

        List<QuestionVO> qvos = new ArrayList<>();
        for (Question q : questions) {
            qvos.add(BeanUtil.copyProperties(q, QuestionVO.class));
        }
        vo.setQuestions(qvos);
        return vo;
    }
}
