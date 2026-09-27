package com.aicoach.controller;

import com.aicoach.common.LimitType;
import com.aicoach.common.RateLimit;
import com.aicoach.common.Result;
import com.aicoach.dto.AnswerResultVO;
import com.aicoach.dto.CreateSessionDTO;
import com.aicoach.dto.SessionVO;
import com.aicoach.dto.SubmitAnswerDTO;
import com.aicoach.service.InterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟面试 Controller
 *
 * - POST /api/interview/create        创建会话（AI 出题），需 JWT
 * - GET  /api/interview/{sessionId}   会话详情，需 JWT
 */
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    /** 每个用户每天最多 10 次 AI 出题（令牌桶限流） */
    @RateLimit(key = "rate:interview", window = 86400, limit = 10, type = LimitType.USER)
    @PostMapping("/create")
    public Result<SessionVO> create(@Valid @RequestBody CreateSessionDTO dto) {
        return Result.success(interviewService.createSession(dto));
    }

    @GetMapping("/{sessionId}")
    public Result<SessionVO> get(@PathVariable Long sessionId) {
        return Result.success(interviewService.getSession(sessionId));
    }

    /** 每个用户每天最多 10 次 AI 评分。
     *  注意：评分同样调用大模型，若不限流，一条循环脚本就能刷爆 API 额度（资损风险）。 */
    @RateLimit(key = "rate:answer", window = 86400, limit = 10, type = LimitType.USER)
    @PostMapping("/answer")
    public Result<AnswerResultVO> submitAnswer(@Valid @RequestBody SubmitAnswerDTO dto) {
        return Result.success(interviewService.submitAnswer(dto));
    }

    /** 轮询评分结果（异步化后，提交只返回 answerId，结果靠这个接口取） */
    @GetMapping("/answer/{answerId}")
    public Result<AnswerResultVO> getAnswerResult(@PathVariable Long answerId) {
        return Result.success(interviewService.getAnswerResult(answerId));
    }
}