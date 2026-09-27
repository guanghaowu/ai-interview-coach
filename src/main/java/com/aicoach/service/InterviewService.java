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
}
