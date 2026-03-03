package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存服务
 * 负责对话数据的 Redis 缓存操作
 */
@Slf4j
@Service
public class RedisCacheService {

    @Value("${conversation.cache.expire-days:7}")
    private int expireDays;

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // Lua 脚本：添加消息
    private DefaultRedisScript<String> addMessageScript;

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String MESSAGES_KEY_SUFFIX = ":messages";
    private static final String RECENT_SESSIONS_KEY = "sessions:recent";
    private static final String DELETED_SESSIONS_KEY = "sessions:deleted"; // 软删除的会话ID集合
    private static final long DEFAULT_TTL_SECONDS = 604800; // 7天

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate, StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // 初始化 Lua 脚本
        addMessageScript = new DefaultRedisScript<>();
        addMessageScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/add_message.lua")));
        addMessageScript.setResultType(String.class); // 关键修改：返回类型改为 String
        log.info("Redis Lua 脚本加载完成");
    }

    /**
     * 创建会话
     */
    public void createSession(String sessionId, String title) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("sessionId", sessionId);
        sessionData.put("title", title);
        sessionData.put("createTime", String.valueOf(System.currentTimeMillis()));
        sessionData.put("updateTime", String.valueOf(System.currentTimeMillis()));
        sessionData.put("messageCount", 0);

        redisTemplate.opsForHash().putAll(sessionKey, sessionData);
        redisTemplate.expire(sessionKey, expireDays, TimeUnit.DAYS);

        // 添加到最近会话列表（使用 StringRedisTemplate 确保一致性）
        stringRedisTemplate.opsForZSet().add(RECENT_SESSIONS_KEY, sessionId, System.currentTimeMillis());

        log.debug("创建会话: {}", sessionId);
    }

    /**
     * 获取会话元数据
     */
    public SessionMetadata getSession(String sessionId) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> data = redisTemplate.opsForHash().entries(sessionKey);

        if (data.isEmpty()) {
            log.debug("Redis中未找到会话: {}", sessionId);
            return null;
        }

        SessionMetadata metadata = new SessionMetadata();
        metadata.setSessionId(sessionId);

        // 【修复 1】安全转换 title - 使用 "新对话" 作为默认值
        Object titleObj = data.get("title");
        String title = titleObj != null ? String.valueOf(titleObj) : null;
        if (title == null || title.isEmpty() || "null".equals(title)) {
            title = "新对话";
        }
        metadata.setTitle(title);

        // 【修复 2】关键修复：使用 String.valueOf 防止 Integer 强转 String 报错
        metadata.setMessageCount(Integer.parseInt(String.valueOf(data.getOrDefault("messageCount", "0"))));
        metadata.setCreateTime(Long.parseLong(String.valueOf(data.getOrDefault("createTime", "0"))));
        metadata.setUpdateTime(Long.parseLong(String.valueOf(data.getOrDefault("updateTime", "0"))));

        log.debug("获取会话元数据: sessionId={}, title={}", sessionId, metadata.getTitle());
        return metadata;
    }

    /**
     * 添加消息 (使用 Lua 脚本原子操作)
     */
    public long addMessage(String sessionId, String role, String content) {
        try {
            // 构建消息 JSON
            Map<String, Object> message = new HashMap<>();
            message.put("role", role);
            message.put("content", content);
            message.put("createTime", System.currentTimeMillis());

            String messageJson = objectMapper.writeValueAsString(message);

            // 执行 Lua 脚本
            // 【修复 3】显式指定序列化器，防止参数带引号导致 Lua 解析失败
            String result = redisTemplate.execute(
                    addMessageScript,
                    RedisSerializer.string(), // argsSerializer
                    RedisSerializer.string(), // resultSerializer
                    Collections.singletonList(sessionId), // keys
                    role, // ARGV[1]
                    messageJson, // ARGV[2]
                    String.valueOf(System.currentTimeMillis()), // ARGV[3]
                    String.valueOf(expireDays * 86400) // ARGV[4]
            );

            // 将结果解析回 Long
            long count = result != null ? Long.parseLong(result) : 0L;

            log.debug("添加消息到 Redis: sessionId={}, role={}, 当前消息数: {}", sessionId, role, count);
            return count;

        } catch (JsonProcessingException e) {
            log.error("消息序列化失败", e);
            return 0;
        }
    }

    /**
     * 获取会话消息列表 (使用 Pipeline 批量获取)
     */
    public List<Message> getMessages(String sessionId, int limit) {
        String messagesKey = SESSION_KEY_PREFIX + sessionId + MESSAGES_KEY_SUFFIX;

        // 使用 Pipeline 批量获取
        List<Object> results = redisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    Long size = connection.lLen(messagesKey.getBytes());
                    // 如果 key 不存在，返回空列表
                    if (size == null || size == 0) {
                        return null;
                    }
                    int count = (int) Math.min(size, limit);
                    for (int i = 0; i < count; i++) {
                        connection.lIndex(messagesKey.getBytes(), i);
                    }
                    return null;
                }
        );

        List<Message> messages = new ArrayList<>();
        if (results != null) {
            for (Object result : results) {
                if (result instanceof String) {
                    try {
                        Message msg = objectMapper.readValue((String) result, Message.class);
                        messages.add(msg);
                    } catch (JsonProcessingException e) {
                        log.warn("解析消息失败: {}", result, e);
                    }
                }
            }
        }

        return messages;
    }

    /**
     * 获取会话消息数量
     */
    public int getMessageCount(String sessionId) {
        SessionMetadata metadata = getSession(sessionId);
        return metadata != null ? metadata.getMessageCount() : 0;
    }

    /**
     * 软删除会话（标记为已删除）
     */
    public void softDeleteSession(String sessionId) {
        // 将会话ID添加到已删除集合
        stringRedisTemplate.opsForSet().add(DELETED_SESSIONS_KEY, sessionId);
        // 从最近会话列表中移除
        stringRedisTemplate.opsForZSet().remove(RECENT_SESSIONS_KEY, sessionId);

        log.info("软删除会话: {}", sessionId);
    }

    /**
     * 检查会话是否已删除
     */
    public boolean isDeleted(String sessionId) {
        return stringRedisTemplate.opsForSet().isMember(DELETED_SESSIONS_KEY, sessionId);
    }

    /**
     * 获取已删除的会话ID集合
     */
    public Set<String> getDeletedSessionIds() {
        try {
            Set<String> members = stringRedisTemplate.opsForSet().members(DELETED_SESSIONS_KEY);
            return members != null ? members : Collections.emptySet();
        } catch (Exception e) {
            log.error("获取已删除会话列表失败", e);
            return Collections.emptySet();
        }
    }

    /**
     * 删除会话（硬删除）
     */
    public void deleteSession(String sessionId) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        String messagesKey = sessionKey + MESSAGES_KEY_SUFFIX;

        redisTemplate.delete(sessionKey);
        redisTemplate.delete(messagesKey);
        stringRedisTemplate.opsForZSet().remove(RECENT_SESSIONS_KEY, sessionId);

        log.debug("删除会话: {}", sessionId);
    }

    /**
     * 获取最近会话列表（排除已删除的）
     */
    public List<String> getRecentSessions(int limit) {
        try {
            // 获取已删除的会话ID
            Set<String> deletedIds = getDeletedSessionIds();

            // 使用 StringRedisTemplate 确保返回的是 String 类型
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(RECENT_SESSIONS_KEY, 0, limit - 1);
            if (members == null) {
                return Collections.emptyList();
            }

            // 过滤掉已删除的会话
            return members.stream()
                    .filter(id -> !deletedIds.contains(id))
                    .toList();
        } catch (Exception e) {
            log.error("获取最近会话列表失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 更新会话标题
     */
    public void updateSessionTitle(String sessionId, String title) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "title", title);
        redisTemplate.opsForHash().put(sessionKey, "updateTime", String.valueOf(System.currentTimeMillis()));
    }

    // ==================== DTO ====================

    @Data
    public static class SessionMetadata {
        private String sessionId;
        private String title;
        private int messageCount;
        private long createTime;
        private long updateTime;
    }

    @Data
    public static class Message {
        private String role;
        private String content;
        private long createTime;
    }
}