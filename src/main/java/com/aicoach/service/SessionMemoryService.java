package com.aicoach.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆服务（Redis）
 *
 * 用 Redis List 存储会话的对话历史，TTL 24h 自动清理。
 * 每次调用 AI 前取最近 N 条作为上下文，模拟真实面试的连续对话。
 *
 * key 格式：interview:memory:{sessionId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMemoryService {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "interview:memory:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_MESSAGES = 20;

    /**
     * 追加一条消息
     *
     * @param role user / assistant
     */
    public void append(Long sessionId, String role, String content) {
        String key = KEY_PREFIX + sessionId;
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        redisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(msg));
        // 只保留最近 MAX_MESSAGES 条，避免无限增长
        redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
        redisTemplate.expire(key, TTL);
    }

    /**
     * 获取最近 N 条消息
     */
    public List<String> getRecent(Long sessionId, int limit) {
        String key = KEY_PREFIX + sessionId;
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return List.of();
        }
        long start = Math.max(0, size - limit);
        List<String> list = redisTemplate.opsForList().range(key, start, -1);
        return list == null ? List.of() : list;
    }

    /**
     * 清空会话记忆
     */
    public void clear(Long sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}