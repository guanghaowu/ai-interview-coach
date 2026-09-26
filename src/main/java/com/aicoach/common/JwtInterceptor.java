package com.aicoach.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 拦截器
 * - 从 Authorization: Bearer xxx 拿 token
 * - 解析出 userId 放到 ThreadLocal
 * - 失败统一返回 401 JSON
 */
@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        // 跨域预检请求放行
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }

        // 拿 token
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(resp, "未登录或 token 缺失");
            return false;
        }
        String token = header.substring(7);

        // 校验 token
        try {
            Long userId = jwtUtil.parseUserId(token);
            ThreadLocalUtil.set(userId);
            return true;
        } catch (Exception e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            writeUnauthorized(resp, "Token 无效或已过期");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        ThreadLocalUtil.clear();
    }

    private void writeUnauthorized(HttpServletResponse resp, String msg) throws Exception {
        resp.setStatus(401);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> result = Result.error(401, msg);
        resp.getWriter().write(new ObjectMapper().writeValueAsString(result));
    }
}