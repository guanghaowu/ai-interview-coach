package com.aicoach.config;

import com.aicoach.common.CacheKeys;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * 响应缓存配置（Spring Cache + Redis）
 *
 * <h3>为什么需要它</h3>
 * 压测显示会话列表接口 p99 达 336.8ms，是全站最大延迟来源（见 docs/性能压测报告.md）。
 * 根因是读路径每次都打 DB，而 Druid 连接池 max-active=20，
 * 高并发下请求排队等连接。缓存命中不占用 DB 连接，是收益最直接的优化。
 *
 * <h3>几个刻意的选择</h3>
 * <ul>
 *   <li><b>用 JSON 而不是默认的 JDK 序列化</b>：JDK 序列化写进 Redis 是二进制乱码，
 *       redis-cli 里完全没法排查；而且要求实体全部实现 Serializable。
 *       JSON 可直接肉眼查看，排障成本低得多。</li>
 *   <li><b>注册 JavaTimeModule 并关闭时间戳输出</b>：VO 里有 LocalDateTime（createdAt），
 *       不注册会直接抛 InvalidDefinitionException；不关闭 WRITE_DATES_AS_TIMESTAMPS
 *       会存成一串数字，可读性差。</li>
 *   <li><b>开启 defaultTyping</b>：PageResultVO&lt;T&gt; 的泛型在运行时被擦除，
 *       不写入类型信息，反序列化时无法还原成 SessionListItemVO，
 *       会得到 LinkedHashMap 导致前端拿到错误结构。</li>
 *   <li><b>分级 TTL</b>：详情是「一次生成、多次读取」，可以缓存久一点；
 *       列表的已答数会随作答变化，用短 TTL 限制最大陈旧窗口。</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                // 默认 60 秒；下面按 cache 名覆盖
                .entryTtl(Duration.ofSeconds(60))
                // 不缓存 null：避免把「查询失败」当成「结果为空」缓存起来，
                // 导致后续请求全部命中空值
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                // 会话详情：题目/回答/反馈一旦生成基本不变，缓存 5 分钟
                CacheKeys.SESSION_DETAIL, base.entryTtl(Duration.ofMinutes(5)),
                // 会话列表：含「已答数」，作答后会变，用 30 秒限制陈旧窗口
                CacheKeys.SESSION_LIST, base.entryTtl(Duration.ofSeconds(30)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // LaissezFaireSubTypeValidator 不做白名单校验。缓存内容完全由本服务写入、
        // 且 Redis 不对外暴露，风险可接受；若 Redis 存在被外部写入的可能，
        // 应换成 BasicPolymorphicTypeValidator 白名单。
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
