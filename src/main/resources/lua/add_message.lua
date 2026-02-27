-- 添加消息到会话 (原子操作)
-- KEYS[1]: sessionId
-- ARGV[1]: role (user/assistant)
-- ARGV[2]: message content (JSON string)
-- ARGV[3]: current timestamp
-- ARGV[4]: TTL in seconds (7 days = 604800)

local sessionId = KEYS[1]
local role = ARGV[1]
local messageJson = ARGV[2]
-- 修改点 1: 强制转为数字 (ZADD 需要数字)
local now = tonumber(ARGV[3])
-- 修改点 2: 强制转为数字 (EXPIRE 需要整数)
local ttl = tonumber(ARGV[4]) or 604800

-- 构建键名
local sessionKey = "session:" .. sessionId
local messagesKey = sessionKey .. ":messages"
local recentSessionsKey = "sessions:recent"

-- 添加消息到列表头部 (最新的在前)
redis.call("LPUSH", messagesKey, messageJson)

-- 保留最近100条消息 (防止内存溢出)
redis.call("LTRIM", messagesKey, 0, 99)

-- 更新会话元数据
redis.call("HINCRBY", sessionKey, "messageCount", 1)
-- 注意: HSET 可以接受字符串或数字，这里用 now 没问题
redis.call("HSET", sessionKey, "updateTime", now)

-- 设置过期时间
-- 这里的 ttl 现在是数字类型了，不会再报错
redis.call("EXPIRE", sessionKey, ttl)
redis.call("EXPIRE", messagesKey, ttl)

-- 更新到最近会话列表 (用时间戳作为score)
redis.call("ZADD", recentSessionsKey, now, sessionId)

-- 限制最近会话列表大小
local recentCount = redis.call("ZCARD", recentSessionsKey)
if recentCount > 100 then
    -- 删除最旧的会话
    local removeCount = recentCount - 100
    redis.call("ZREMRANGEBYRANK", recentSessionsKey, 0, removeCount - 1)
end

-- 返回当前消息总数
return redis.call("HGET", sessionKey, "messageCount")