package com.aicoach.service;

import com.aicoach.constant.SessionStatus;
import com.aicoach.dto.QuestionDTO;
import com.aicoach.entity.InterviewSession;
import com.aicoach.mapper.InterviewSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话生命周期服务
 *
 * 为什么要有这个类：
 *
 * 1. **事务边界**。原来「建会话」「复制题目」「改状态」散在 InterviewServiceImpl 与
 *    InterviewConsumer 里，有的带事务有的不带；更隐蔽的是，private 方法自调用时
 *    Spring 的 @Transactional 会静默失效（不走代理）。集中到这里之后，
 *    每个方法的事务语义一目了然，也不会再踩自调用的坑。
 *
 * 2. **消除 Shotgun Surgery**。会话状态是同一份数据，任何关于它的改动
 *    都应该只落在这一个文件里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final InterviewSessionMapper sessionMapper;
    private final QuestionService questionService;

    /**
     * 新建「出题中」会话。
     *
     * 独立事务：方法返回时已经提交。调用方随后再投递 MQ 才不会出现
     * 「消息先到、消费端查不到会话」的时序问题。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSession createPending(Long userId, String jdMd5, String jdContent) {
        InterviewSession session = newSession(userId, jdMd5, jdContent,
                SessionStatus.GENERATING, "解析中", "解析中");
        sessionMapper.insert(session);
        log.info("会话已创建（待 AI 出题）: sessionId={}, userId={}", session.getId(), userId);
        return session;
    }

    /**
     * 复用历史题目新建会话。
     *
     * 「建会话 + 复制题目」必须同一事务：否则复制中途失败会留下
     * 「status=已完成但 0 道题」的空会话，前端拿到空题目的成功态。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSession reuseFromHistory(Long userId, String jdMd5, String jdContent, Long sourceSessionId) {
        InterviewSession session = newSession(userId, jdMd5, jdContent,
                SessionStatus.DONE, "复用缓存", "复用缓存");
        sessionMapper.insert(session);

        int copied = questionService.copyTo(sourceSessionId, session.getId()).size();
        log.info("命中 JD 缓存，复用题目: sessionId={}, 复用 {} 道题", session.getId(), copied);
        return session;
    }

    /**
     * 幂等复用：把源会话的题目复制到目标会话，并把目标会话置为完成。
     *
     * 复制与改状态必须同一事务——否则复制中途失败会留下「题目不全却已完成」的会话：
     * MQ 重投时 {@code countBySession > 0} 会直接判定为已完成，前端拿到残缺题目。
     */
    @Transactional(rollbackFor = Exception.class)
    public int copyQuestionsAndMarkDone(Long sourceSessionId, Long targetSessionId) {
        int copied = questionService.copyTo(sourceSessionId, targetSessionId).size();

        InterviewSession target = sessionMapper.selectById(targetSessionId);
        if (target != null) {
            target.setStatus(SessionStatus.DONE.getCode());
            // 复用历史题目没跑过 AgentLoop，轮数记 0（语义：非 Agent 产出）
            target.setAgentRounds(0);
            sessionMapper.updateById(target);
        }
        log.info("幂等复用完成: targetSessionId={}, 复用 {} 道题", targetSessionId, copied);
        return copied;
    }

    /**
     * AgentLoop 出题成功后落库：写题目 + 置为完成 + 记录执行轮数。
     *
     * 与 {@link #copyQuestionsAndMarkDone} 同理，「题目」与「会话状态」必须同一事务：
     * 否则中途失败会留下「题目不全却已完成」的会话。
     *
     * @param agentRounds AgentLoop 实际执行轮数，落库后可用于展示「Agent 跑了几轮」
     * @return 落库题目数
     */
    @Transactional(rollbackFor = Exception.class)
    public int saveQuestionsAndMarkDone(Long sessionId, List<QuestionDTO> questions, int agentRounds) {
        int saved = questionService.saveGenerated(sessionId, questions);

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setStatus(SessionStatus.DONE.getCode());
            session.setAgentRounds(agentRounds);
            sessionMapper.updateById(session);
        }
        log.info("出题落库完成: sessionId={}, 题目数={}, agent 轮数={}", sessionId, saved, agentRounds);
        return saved;
    }

    /**
     * 更新会话状态。
     *
     * 用 {@code REQUIRES_NEW}：调用方可能处在「事务已提交但资源尚未解绑」的时机
     * （例如 MQ 投递失败后的补偿逻辑），必须开新事务才能可靠落库。
     *
     * 内置守卫：**已完成的会话不允许被改判为失败**。异常常常发生在业务成功之后
     * （幂等标记撞唯一键、写会话记忆失败等），无脑覆盖会把成功抹成失败，
     * 前端就会看到状态从 1 抖到 2。
     *
     * @return 是否真的发生了更新
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean updateStatus(Long sessionId, SessionStatus target) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return false;
        }
        if (SessionStatus.DONE.matches(session.getStatus()) && target != SessionStatus.DONE) {
            log.warn("会话已是完成态，拒绝改判: sessionId={}, target={}", sessionId, target);
            return false;
        }
        if (target.matches(session.getStatus())) {
            return false;
        }
        session.setStatus(target.getCode());
        sessionMapper.updateById(session);
        return true;
    }

    /** 构造会话实体：创建与复用两条路径共用，避免字段漏设 */
    private InterviewSession newSession(Long userId, String jdMd5, String jdContent,
                                        SessionStatus status, String position, String techStack) {
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setJdMd5(jdMd5);
        session.setJdContent(jdContent);
        session.setPosition(position);
        session.setTechStack(techStack);
        session.setStatus(status.getCode());
        return session;
    }
}
