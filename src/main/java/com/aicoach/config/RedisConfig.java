package com.aicoach.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 配置：注册 Lua 脚本
 */
@Configuration
public class RedisConfig {

    /**
     * 令牌桶限流脚本（返回 1=放行 0=限流）
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/token_bucket.lua"));
        script.setResultType(Long.class);
        return script;
    }
}