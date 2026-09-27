package com.aicoach.controller;

import com.aicoach.common.LimitType;
import com.aicoach.common.RateLimit;
import com.aicoach.common.Result;
import com.aicoach.dto.AnswerResultVO;
import com.aicoach.dto.CreateSessionDTO;
import com.aicoach.dto.PageResultVO;
import com.aicoach.dto.SessionDetailVO;
import com.aicoach.dto.SessionListItemVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟面试 Controller
 *
 * - POST /api/interview/create                创建会话（AI 异步出题），需 JWT
 * - GET  /api/interview/sessions              我的会话列表（分页）
 * - GET  /api/interview/sessions/{id}         会话完整详情（题目 + 回答 + 反馈）
 * - GET  /api/interview/{sessionId}           会话详情（含题目），轮询出题结果
 * - POST /api/interview/answer                提交回答（AI 异步评分）
 * - GET  /api/interview/answer/{answerId}     轮询评分结果
 * - POST /api/interview/{sessionId}/retry     手动重试出题（会话失败时可用）
 *
 * 说明：/sessions 是字面量路径，/{sessionId} 是变量路径。
 * Spring Boot 3 的 PathPatternParser 会优先匹配更具体的字面量，与声明顺序无关，
 * 所以 "sessions" 不会被当成 sessionId 吃掉。
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

    /** 我的会话列表（分页） */
    @GetMapping("/sessions")
    public Result<PageResultVO<SessionListItemVO>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(interviewService.listSessions(page, size));
    }

    /** 会话完整详情：题目 + 每题最新回答 + 评分反馈 */
    @GetMapping("/sessions/{sessionId}")
    public Result<SessionDetailVO> sessionDetail(@PathVariable Long sessionId) {
        return Result.success(interviewService.getSessionDetail(sessionId));
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

    /** 会话详情（含题目），出题中时前端靠这个接口轮询 */
    @GetMapping("/{sessionId}")
    public Result<SessionVO> get(@PathVariable Long sessionId) {
        return Result.success(interviewService.getSession(sessionId));
    }

    /**
     * 手动重试出题（仅当会话处于失败态）。
     *
     * 自动兜底（死信落库 + 定时重投）之外的用户自救路径。
     * 同样要限流——重试会真实调用 AI，用独立令牌桶（5 次/天），
     * 不去挤占正常出题的 10 次配额。
     */
    @RateLimit(key = "rate:interview:retry", window = 86400, limit = 5, type = LimitType.USER)
    @PostMapping("/{sessionId}/retry")
    public Result<SessionVO> retry(@PathVariable Long sessionId) {
        return Result.success(interviewService.retrySession(sessionId));
    }
}
