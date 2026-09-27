package com.aicoach.service;

import com.aicoach.common.CacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应缓存失效服务
 *
 * <h3>为什么写侧用显式失效，而不是 {@code @CacheEvict}</h3>
 * 读路径用 {@code @Cacheable} 声明式很清晰，但写路径的失效有它表达不了的两点：
 *
 * <ol>
 *   <li><b>要按 userId 精准失效，而不是清空整个 cache。</b>
 *       会话列表的 key 是 {@code userId:page:size}，一个用户有多个分页。
 *       如果用 {@code @CacheEvict(allEntries = true)}，任意一个用户建会话
 *       都会把所有用户的列表缓存清空 —— 那是「用缓存的名头，干着没有缓存的事」。</li>
 *   <li><b>sessionId 在方法签名里拿不到。</b>
 *       比如提交回答时 sessionId 是从题目反查出来的，SpEL 表达式无从引用。</li>
 * </ol>
 *
 * <h3>为什么用 SCAN 而不是 KEYS</h3>
 * {@code KEYS} 会遍历整个键空间且**阻塞 Redis 单线程**，键一多就会拖垮整个实例
 * （不只是缓存失效，所有依赖 Redis 的功能一起卡住：限流、会话记忆、幂等）。
 * {@code SCAN} 是游标式增量遍历，不阻塞，代价是可能返回重复 key（对本场景无影响）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseCacheService {

    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;

    /**
     * 精准失效「某用户的某个会话」详情缓存。
     * 只影响这一个 key，不波及其他用户、不波及其他会话。
     */
    public void evictSessionDetail(Long userId, Long sessionId) {
        Cache cache = cacheManager.getCache(CacheKeys.SESSION_DETAIL);
        if (cache == null) {
            return;
        }
        cache.evict(CacheKeys.sessionDetail(userId, sessionId));
    }

    /**
     * 失效「某用户」的全部会话列表分页缓存。
     *
     * 依赖 Spring Data Redis 的 RedisCache 默认 key 格式 {@code cacheName::key}。
     * 万一该格式将来变化，这里匹配不到 key —— 但**不会导致数据错误**：
     * 列表缓存 TTL 只有 30 秒，最坏情况是数据陈旧 30 秒后自动消失。
     * 这是刻意保留的降级路径，而不是靠「格式永远不变」的假设。
     */
    public void evictSessionList(Long userId) {
        Cache cache = cacheManager.getCache(CacheKeys.SESSION_LIST);
        if (cache == null) {
            return;
        }
        String pattern = cache.getName() + "::" + CacheKeys.sessionListPrefix(userId) + "*";

        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }

        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("已失效会话列表缓存: userId={}, 清除 {} 个分页", userId, keys.size());
        }
    }
}
