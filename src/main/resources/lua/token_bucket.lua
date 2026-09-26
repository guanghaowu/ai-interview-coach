-- =====================================================
-- 令牌桶限流 Lua 脚本（原子执行）
-- KEYS[1] : 桶的 key
-- ARGV[1] : 桶容量 capacity
-- ARGV[2] : 令牌补充速率（个/秒）
-- ARGV[3] : 当前时间戳（毫秒）
-- ARGV[4] : 本次请求令牌数
-- 返回     : 1 = 放行，0 = 限流
-- =====================================================
local key        = KEYS[1]
local capacity   = tonumber(ARGV[1])
local rate       = tonumber(ARGV[2])
local now        = tonumber(ARGV[3])
local requested  = tonumber(ARGV[4])

local bucket     = redis.call('HMGET', key, 'tokens', 'lastRefill')
local tokens     = tonumber(bucket[1])
local lastRefill = tonumber(bucket[2])

-- 首次访问：桶满
if tokens == nil then
    tokens = capacity
    lastRefill = now
end

-- 按流逝时间补充令牌
local delta = math.max(0, now - lastRefill)
local refill = math.floor(delta * rate / 1000)
if refill > 0 then
    tokens = math.min(capacity, tokens + refill)
    lastRefill = now
end

-- 判断是否放行
local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

-- 写回状态
redis.call('HMSET', key, 'tokens', tokens, 'lastRefill', lastRefill)
-- 过期时间：桶被填满所需时间的 2 倍，避免冷 key 常驻
local ttl = math.ceil(capacity / rate * 2)
if ttl < 60 then ttl = 60 end
redis.call('EXPIRE', key, ttl)

return allowed
