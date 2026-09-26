package com.aicoach.service;

import com.aicoach.dto.AnswerResultVO;
import com.aicoach.dto.CreateSessionDTO;
import com.aicoach.dto.SessionVO;
import com.aicoach.dto.SubmitAnswerDTO;

/**
 * 模拟面试服务
 */
public interface InterviewService {

    /** 创建面试会话：AI 出题 + 落库 */
    SessionVO createSession(CreateSessionDTO dto);

    /** 获取会话详情（含题目） */
    SessionVO getSession(Long sessionId);

    /** 提交回答并获取 AI 评分 */
    AnswerResultVO submitAnswer(SubmitAnswerDTO dto);
}