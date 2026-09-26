package com.aicoach.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口（Day 1 验证脚手架用）
 *
 * 启动后访问: http://localhost:8080/api/health
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "ai-interview-coach");
        result.put("version", "0.0.1-SNAPSHOT");
        result.put("timestamp", LocalDateTime.now());
        result.put("day", "Day 1 - 脚手架已完成");
        return result;
    }
}