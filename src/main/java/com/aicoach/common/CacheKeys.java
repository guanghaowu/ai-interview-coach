package com.aicoach.common;

/**
 * 缓存名称与 key 构造
 *
 * 为什么把 key 构造单独抽出来：
 * 缓存 key 的格式在**读路径**（{@code @Cacheable} 的 SpEL 表达式）与
 * **写路径**（失效逻辑）两处都要用到。一旦两边格式不一致，就会出现
 * 「写进去了但永远失效不掉」的幽灵缓存 —— 而且这种 bug 不会报错，
 * 只会表现为「数据莫名其妙不更新」，极难排查。集中在这里保证只有一处定义。
 *
 * <b>安全红线：所有 key 都必须以 userId 开头。</b>
 * 如果 key 只用 sessionId，A 用户请求 B 用户的会话时会直接命中 B 的缓存，
 * 从而绕过 {@code requireOwnedSession} 的 403 归属校验 —— 这是越权读。
 * 把 userId 放进 key，越权请求自然落到不同的 key 上，必然 miss，照样走校验逻辑。
 */
public final class CacheKeys {

    /** 会话详情缓存（key: userId:sessionId） */
    public static final String SESSION_DETAIL = "sessionDetail";

    /** 会话列表缓存（key: userId:page:size） */
    public static final String SESSION_LIST = "sessionList";

    private CacheKeys() {
    }

    /** 会话详情 key。写路径（失效）用它，userId 由调用方显式传入 */
    public static String sessionDetail(Long userId, Long sessionId) {
        return userId + ":" + sessionId;
    }

    /**
     * 会话详情 key，供 {@code @Cacheable} 的 SpEL 使用。
     *
     * 这里从 ThreadLocal 取当前用户而不是用方法参数：因为 service 方法的签名里
     * 根本没有 userId（它由 JwtInterceptor 解析 token 后放进 ThreadLocal）。
     * 拦截器保证进入 controller 前一定已 set 过，所以这里不会是 null。
     */
    public static String sessionDetailFor(Long sessionId) {
        return sessionDetail(ThreadLocalUtil.get(), sessionId);
    }

    /** 会话列表 key。写路径用它 */
    public static String sessionList(Long userId, int page, int size) {
        return userId + ":" + page + ":" + size;
    }

    /** 会话列表 key，供 SpEL 使用（userId 同样来自 ThreadLocal） */
    public static String sessionListFor(int page, int size) {
        return sessionList(ThreadLocalUtil.get(), page, size);
    }

    /**
     * 某用户会话列表缓存的 key 前缀，用于按用户批量失效所有分页。
     * 形如 {@code 7:}，配合 SCAN 匹配 {@code sessionList::7:*}
     */
    public static String sessionListPrefix(Long userId) {
        return userId + ":";
    }
}
