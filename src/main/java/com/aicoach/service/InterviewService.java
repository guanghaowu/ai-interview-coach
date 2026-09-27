package com.aicoach.service;

import com.aicoach.dto.AnswerResultVO;
import com.aicoach.dto.CreateSessionDTO;
import com.aicoach.dto.PageResultVO;
import com.aicoach.dto.SessionDetailVO;
import com.aicoach.dto.SessionListItemVO;
import com.aicoach.dto.SessionVO;
import com.aicoach.dto.SubmitAnswerDTO;

/**
 * 模拟面试服务
 */
public interface InterviewService {

    /** 创建面试会话：立即返回，AI 出题异步执行 */
    SessionVO createSession(CreateSessionDTO dto);

    /** 获取会话详情（含题目），出题中时前端靠这个接口轮询 */
    SessionVO getSession(Long sessionId);

    /** 我的会话列表（分页） */
    PageResultVO<SessionListItemVO> listSessions(int page, int size);

    /** 会话完整详情：题目 + 每题最新回答 + 评分反馈 */
    SessionDetailVO getSessionDetail(Long sessionId);

    /** 提交回答：立即返回 answerId，AI 评分异步执行 */
    AnswerResultVO submitAnswer(SubmitAnswerDTO dto);

    /** 查询评分结果（轮询用） */
    AnswerResultVO getAnswerResult(Long answerId);

    /**
     * 手动重试出题（会话处于失败态时可用）。
     *
     * 自动兜底（死信落库 + 定时重投）之外还要留这条路径，原因有二：
     * 一是自动重投次数用尽后用户仍有自救手段，不必等人工介入；
     * 二是用户就在页面上看着失败，让他点一下就恢复，比让他等 5 分钟体验好得多。
     */
    SessionVO retrySession(Long sessionId);
}
