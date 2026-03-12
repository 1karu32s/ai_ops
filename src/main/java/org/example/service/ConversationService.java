package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.ChatMessage;
import org.example.entity.ChatSession;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.ChatSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 对话服务
 * Redis + MySQL 混合存储，异步写入 MySQL
 */
@Slf4j
@Service
public class ConversationService {

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ConversationSummaryService conversationSummaryService;

    @Autowired
    private MessagePersistService messagePersistService;

    @Autowired
    @Qualifier("persistExecutor")
    private Executor persistExecutor;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String SYNC_LOCK_KEY = "sync:redis_to_mysql:lock";
    private static final long SYNC_LOCK_TIMEOUT = 4 * 60 * 1000; // 4分钟锁超时（任务5分钟执行一次）

    // 缓存击穿防护锁
    private static final String CACHE_LOCK_PREFIX = "lock:cache:";
    private static final int CACHE_LOCK_TIMEOUT = 30; // 30秒锁超时

    /**
     * 获取或创建会话（DCL 防缓存击穿）
     */
    public String getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 1. 先查 Redis
        RedisCacheService.SessionMetadata metadata = redisCacheService.getSession(sessionId);
        if (metadata != null) {
            return sessionId;
        }

        // 2. Redis 未命中，加锁
        String lockKey = CACHE_LOCK_PREFIX + "session:" + sessionId;
        boolean locked = tryAcquireLock(lockKey);

        if (locked) {
            try {
                // 3. 双重检查
                metadata = redisCacheService.getSession(sessionId);
                if (metadata != null) {
                    return sessionId;
                }

                // 4. 查 MySQL
                ChatSession dbSession = chatSessionMapper.selectOne(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getSessionId, sessionId)
                );

                if (dbSession != null) {
                    // 回填 Redis
                    redisCacheService.createSession(sessionId, dbSession.getTitle());
                    log.debug("从 MySQL 回填会话到 Redis: {}", sessionId);
                } else {
                    // 创建新会话
                    redisCacheService.createSession(sessionId, "新对话");
                    // 异步持久化到 MySQL
                    persistSession(sessionId, "新对话");
                    log.debug("创建新会话: {}", sessionId);
                }
            } finally {
                releaseLock(lockKey);
            }
        } else {
            // 5. 未获取到锁，等待后重试
            waitForCache(sessionId);
            // 不需要再查，缓存可能在其他请求中已回填
        }

        return sessionId;
    }

    /**
     * 添加消息对 (用户消息 + AI回复)
     * 同步写入 Redis，异步写入 MySQL
     */
    public void addMessagePair(String sessionId, String userMessage, String aiReply) {
        long startTime = System.currentTimeMillis();

        // 1. 同步写入 Redis (快速响应)
        redisCacheService.addMessage(sessionId, "user", userMessage);
        redisCacheService.addMessage(sessionId, "assistant", aiReply);

        // 更新会话标题（如果还没有标题，使用第一条用户消息的前30个字符）
        updateSessionTitleIfNeeded(sessionId, userMessage);

        long redisTime = System.currentTimeMillis();
        log.debug("Redis 写入耗时: {}ms", redisTime - startTime);

        // 2. 异步持久化到 MySQL (不阻塞响应，带重试机制)
        persistExecutor.execute(() -> {
            persistWithRetry(sessionId, userMessage, aiReply, startTime);
        });
    }

    /**
     * 如果会话没有标题，则设置为用户消息的前30个字符
     * 统一逻辑：Redis + 异步写 MySQL
     */
    private void updateSessionTitleIfNeeded(String sessionId, String userMessage) {
        try {
            RedisCacheService.SessionMetadata metadata = redisCacheService.getSession(sessionId);
            if (metadata != null) {
                String currentTitle = metadata.getTitle();
                // 如果标题是空的或者是默认的"新对话"，则更新
                if (currentTitle == null || currentTitle.isEmpty() ||
                    "新对话".equals(currentTitle) || "New Chat".equals(currentTitle)) {
                    String newTitle = userMessage.length() > 30 ?
                        userMessage.substring(0, 30) + "..." : userMessage;
                    redisCacheService.updateSessionTitle(sessionId, newTitle);
                    // 异步持久化到 MySQL
                    persistSessionTitleAsync(sessionId, newTitle);
                    log.debug("更新会话标题: sessionId={}, title={}", sessionId, newTitle);
                }
            }
        } catch (Exception e) {
            log.warn("更新会话标题失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取会话消息列表（DCL 防缓存击穿）
     * Cache-Aside 模式
     */
    public List<RedisCacheService.Message> getMessages(String sessionId, int limit) {
        // 1. 先查 Redis
        List<RedisCacheService.Message> messages = redisCacheService.getMessages(sessionId, limit);

        if (!messages.isEmpty()) {
            return messages;
        }

        // 2. Redis 未命中，加锁
        String lockKey = CACHE_LOCK_PREFIX + "messages:" + sessionId;
        boolean locked = tryAcquireLock(lockKey);

        List<ChatMessage> dbMessages;
        if (locked) {
            try {
                // 3. 双重检查
                messages = redisCacheService.getMessages(sessionId, limit);
                if (!messages.isEmpty()) {
                    return messages;
                }

                // 4. 查 MySQL
                dbMessages = chatMessageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getSessionId, sessionId)
                                .orderByAsc(ChatMessage::getCreateTime)
                                .last("LIMIT " + limit)
                );

                if (!dbMessages.isEmpty()) {
                    // 5. 回填 Redis
                    for (ChatMessage msg : dbMessages) {
                        redisCacheService.addMessage(sessionId, msg.getRole(), msg.getContent());
                    }
                    log.debug("从 MySQL 回填消息到 Redis: sessionId={}, count={}", sessionId, dbMessages.size());
                }
            } finally {
                releaseLock(lockKey);
            }
        } else {
            // 6. 未获取到锁，等待后重试从 Redis 获取
            waitForCache(sessionId);
            messages = redisCacheService.getMessages(sessionId, limit);
            if (!messages.isEmpty()) {
                return messages;
            }
            // 仍然没有，返回空（避免阻塞）
            return Collections.emptyList();
        }

        // 7. 转换为 DTO
        return dbMessages.stream().map(msg -> {
            RedisCacheService.Message m = new RedisCacheService.Message();
            m.setRole(msg.getRole());
            m.setContent(msg.getContent());
            m.setCreateTime(msg.getCreateTime().getTime());
            return m;
        }).toList();
    }

    /**
     * 获取会话元数据（DCL 防缓存击穿）
     */
    public RedisCacheService.SessionMetadata getSessionMetadata(String sessionId) {
        // 1. 先查 Redis
        RedisCacheService.SessionMetadata metadata = redisCacheService.getSession(sessionId);

        if (metadata != null) {
            return metadata;
        }

        // 2. Redis 未命中，加锁
        String lockKey = CACHE_LOCK_PREFIX + "session:" + sessionId;
        boolean locked = tryAcquireLock(lockKey);

        if (locked) {
            try {
                // 3. 双重检查
                metadata = redisCacheService.getSession(sessionId);
                if (metadata != null) {
                    return metadata;
                }

                // 4. 查 MySQL
                ChatSession dbSession = chatSessionMapper.selectOne(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getSessionId, sessionId)
                );

                if (dbSession != null) {
                    // 5. 回填 Redis
                    redisCacheService.createSession(sessionId, dbSession.getTitle());
                    metadata = new RedisCacheService.SessionMetadata();
                    metadata.setSessionId(dbSession.getSessionId());
                    metadata.setTitle(dbSession.getTitle());
                    metadata.setMessageCount(dbSession.getMessageCount());
                    metadata.setCreateTime(dbSession.getCreateTime().getTime());
                    metadata.setUpdateTime(dbSession.getUpdateTime().getTime());
                }
            } finally {
                releaseLock(lockKey);
            }
        } else {
            // 6. 未获取到锁，等待后重试
            waitForCache(sessionId);
            metadata = redisCacheService.getSession(sessionId);
        }

        return metadata;
    }

    /**
     * 清空会话历史
     */
    public void clearHistory(String sessionId) {
        // 删除 Redis 缓存
        redisCacheService.deleteSession(sessionId);

        // 重新创建空会话
        redisCacheService.createSession(sessionId, "新对话");

        // 异步删除 MySQL 数据（带重试机制）
        persistExecutor.execute(() -> {
            clearWithRetry(sessionId);
        });
    }

    /**
     * 软删除会话
     */
    public void softDeleteSession(String sessionId) {
        // 软删除 Redis 缓存
        redisCacheService.softDeleteSession(sessionId);

        // 异步持久化删除状态到 MySQL
        persistExecutor.execute(() -> {
            try {
                ChatSession session = chatSessionMapper.selectOne(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getSessionId, sessionId)
                );
                if (session != null) {
                    session.setDeleted(true);
                    chatSessionMapper.updateById(session);
                    log.debug("会话已标记为删除: {}", sessionId);
                }
            } catch (Exception e) {
                log.error("标记会话删除失败: {}", sessionId, e);
            }
        });
    }

    /**
     * 修改会话标题
     * 统一逻辑：Redis + 异步写 MySQL
     */
    public void updateSessionTitle(String sessionId, String newTitle) {
        // 1. 先更新 Redis（缓存）
        redisCacheService.updateSessionTitle(sessionId, newTitle);
        log.debug("Redis会话标题已更新: sessionId={}, newTitle={}", sessionId, newTitle);

        // 2. 异步持久化到 MySQL
        persistSessionTitleAsync(sessionId, newTitle);
    }

    /**
     * 获取最近会话列表
     * 优先从 Redis 获取，如果为空则从 MySQL 获取并回填 Redis
     */
    public List<String> getRecentSessions(int limit) {
        // 先从 Redis 获取
        List<String> sessionIds = redisCacheService.getRecentSessions(limit);

        // 如果 Redis 为空，从 MySQL 获取并回填
        if (sessionIds.isEmpty()) {
            List<ChatSession> dbSessions = chatSessionMapper.selectList(
                    new LambdaQueryWrapper<ChatSession>()
                            .eq(ChatSession::getDeleted, false) // 过滤已删除的会话
                            .or()
                            .isNull(ChatSession::getDeleted) // 兼容旧数据
                            .orderByDesc(ChatSession::getUpdateTime)
                            .last("LIMIT " + limit)
            );

            // 回填到 Redis
            for (ChatSession session : dbSessions) {
                redisCacheService.createSession(session.getSessionId(), session.getTitle());
            }

            sessionIds = dbSessions.stream()
                    .map(ChatSession::getSessionId)
                    .toList();

            log.info("从 MySQL 回填 {} 个会话到 Redis", sessionIds.size());
        }

        return sessionIds;
    }

    /**
     * 获取会话元数据列表（完整信息）
     */
    public List<RedisCacheService.SessionMetadata> getRecentSessionsWithMetadata(int limit) {
        List<String> sessionIds = getRecentSessions(limit);
        List<RedisCacheService.SessionMetadata> sessions = new ArrayList<>();

        for (String sessionId : sessionIds) {
            RedisCacheService.SessionMetadata metadata = getSessionMetadata(sessionId);
            if (metadata != null) {
                sessions.add(metadata);
            }
        }

        return sessions;
    }

    // ==================== 私有方法 ====================

    /**
     * 持久化会话到 MySQL
     */
    private void persistSession(String sessionId, String title) {
        try {
            ChatSession session = new ChatSession();
            session.setSessionId(sessionId);
            session.setTitle(title);
            session.setMessageCount(0);
            chatSessionMapper.insert(session);
            log.debug("会话已持久化到 MySQL: {}", sessionId);
        } catch (Exception e) {
            log.error("持久化会话失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 异步持久化会话标题到 MySQL
     */
    private void persistSessionTitleAsync(String sessionId, String title) {
        persistExecutor.execute(() -> {
            try {
                ChatSession session = chatSessionMapper.selectOne(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getSessionId, sessionId)
                );
                if (session != null) {
                    session.setTitle(title);
                    chatSessionMapper.updateById(session);
                    log.debug("会话标题已异步持久化到 MySQL: sessionId={}, title={}", sessionId, title);
                }
            } catch (Exception e) {
                log.error("异步持久化会话标题失败: sessionId={}, title={}", sessionId, title, e);
            }
        });
    }

    /**
     * 带重试机制的持久化
     * 指数退避重试，最大重试3次，如果仍然失败则记录到待重试列表
     */
    private void persistWithRetry(String sessionId, String userMessage, String aiReply, long startTime) {
        int maxRetries = 3;
        int retryCount = 0;
        boolean success = false;

        while (retryCount < maxRetries && !success) {
            try {
                messagePersistService.persistMessagePair(sessionId, userMessage, aiReply);
                success = true;
                long dbTime = System.currentTimeMillis();
                log.debug("MySQL 异步写入完成，总耗时: {}ms", dbTime - startTime);

                // 持久化成功后，检查是否需要触发压缩
                conversationSummaryService.triggerSummaryCompression(sessionId);

            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    // 达到最大重试次数，记录到待重试列表
                    log.error("异步持久化消息失败，已达最大重试次数({}): sessionId={}, error={}",
                            maxRetries, sessionId, e.getMessage(), e);
                    recordFailedSession(sessionId);
                    return;
                }

                // 判断是否可重试的异常类型
                boolean isRetryable = isRetryableException(e);
                if (!isRetryable) {
                    log.error("异步持久化消息失败，不可重试异常: sessionId={}, error={}",
                            sessionId, e.getMessage(), e);
                    recordFailedSession(sessionId);
                    return;
                }

                // 计算退避时间
                long backoffTime = 1000L * (1L << (retryCount - 1)); // 1s, 2s, 4s
                log.warn("异步持久化消息失败，准备重试 ({}/{}): sessionId={}, 等待{}ms后重试, error={}",
                        retryCount, maxRetries, sessionId, backoffTime, e.getMessage());

                try {
                    Thread.sleep(backoffTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断: sessionId={}", sessionId);
                    recordFailedSession(sessionId);
                    return;
                }
            }
        }
    }

    /**
     * 记录失败的 session 到待重试列表
     */
    private void recordFailedSession(String sessionId) {
        try {
            String key = "sync:failed_sessions";
            stringRedisTemplate.opsForSet().add(key, sessionId);
            stringRedisTemplate.expire(key, Duration.ofHours(24)); // 24小时后过期
            log.debug("已记录待重试会话: {}", sessionId);
        } catch (Exception e) {
            log.error("记录失败会话失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 判断异常是否可重试
     */
    private boolean isRetryableException(Exception e) {
        String message = e.getMessage();
        if (message == null) return false;

        // 可重试的异常类型
        String retryablePatterns[] = {
            "Connection", "timeout", "Temporary failure", "Network",
            "连接", "超时", "网络", "临时", "锁", "Lock"
        };

        for (String pattern : retryablePatterns) {
            if (message.contains(pattern)) {
                return true;
            }
        }

        // 根据异常类型判断
        return e instanceof java.sql.SQLException;
    }

    /**
     * 带重试机制的清空会话历史
     * 指数退避重试，最大重试3次
     */
    private void clearWithRetry(String sessionId) {
        int maxRetries = 3;
        int retryCount = 0;
        boolean success = false;

        while (retryCount < maxRetries && !success) {
            try {
                // 删除所有消息
                chatMessageMapper.delete(
                        new LambdaQueryWrapper<ChatMessage>()
                                .eq(ChatMessage::getSessionId, sessionId)
                );
                // 重置会话
                ChatSession session = chatSessionMapper.selectOne(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getSessionId, sessionId)
                );
                if (session != null) {
                    session.setMessageCount(0);
                    session.setTitle("新对话");
                    chatSessionMapper.updateById(session);
                }
                log.info("会话历史已清空: {}", sessionId);
                success = true;

            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    log.error("清空会话历史失败，已达最大重试次数({}): sessionId={}, error={}",
                            maxRetries, sessionId, e.getMessage(), e);
                    return;
                }

                // 判断是否可重试
                boolean isRetryable = isRetryableException(e);
                if (!isRetryable) {
                    log.error("清空会话历史失败，不可重试异常: sessionId={}, error={}",
                            sessionId, e.getMessage(), e);
                    return;
                }

                // 计算退避时间
                long backoffTime = 1000L * (1L << (retryCount - 1)); // 1s, 2s, 4s
                log.warn("清空会话历史失败，准备重试 ({}/{}): sessionId={}, 等待{}ms后重试, error={}",
                        retryCount, maxRetries, sessionId, backoffTime, e.getMessage());

                try {
                    Thread.sleep(backoffTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断: sessionId={}", sessionId);
                    return;
                }
            }
        }
    }

    /**
     * 定时重试同步失败的会话
     * 每5分钟执行一次，只重试之前写入失败的 session
     */
    @Scheduled(fixedRate = 5 * 60 * 1000) // 5分钟
    public void syncRedisToMySQL() {
        // 1. 获取分布式锁，防止多实例同时执行
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(SYNC_LOCK_KEY, "1", Duration.ofMillis(SYNC_LOCK_TIMEOUT));

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("上次同步任务仍在执行中，跳过本次执行");
            return;
        }

        try {
            // 2. 获取待重试的 session 列表
            String failedKey = "sync:failed_sessions";
            Set<String> failedSessionIds = stringRedisTemplate.opsForSet().members(failedKey);

            if (failedSessionIds == null || failedSessionIds.isEmpty()) {
                log.info("没有需要重试的会话");
                return;
            }

            log.info("发现 {} 个会话需要重试同步", failedSessionIds.size());

            int successCount = 0;
            int failCount = 0;

            for (String sessionId : failedSessionIds) {
                try {
                    // 尝试重新持久化该 session 的最新消息
                    if (retrySyncSession(sessionId)) {
                        // 成功后从待重试列表移除
                        stringRedisTemplate.opsForSet().remove(failedKey, sessionId);
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("重试同步会话失败: sessionId={}", sessionId, e);
                }
            }

            log.info("重试同步完成: 成功={}, 仍失败={}", successCount, failCount);

        } catch (Exception e) {
            log.error("定时同步任务执行失败", e);
        } finally {
            // 3. 释放锁
            stringRedisTemplate.delete(SYNC_LOCK_KEY);
        }
    }

    /**
     * 重试同步单个会话
     * 获取该 session 在 Redis 中的最新消息，尝试重新持久化
     * @return true 如果同步成功
     */
    private boolean retrySyncSession(String sessionId) {
        try {
            // 获取 Redis 中的消息
            RedisCacheService.SessionMetadata metadata = redisCacheService.getSession(sessionId);
            if (metadata == null) {
                // Redis 会话已过期，删除待重试记录
                log.warn("待重试会话已从 Redis 过期: {}", sessionId);
                return true;
            }

            List<RedisCacheService.Message> redisMessages = redisCacheService.getMessages(sessionId, 1000);
            if (redisMessages.isEmpty()) {
                return true;
            }

            // 获取 MySQL 中的消息数量
            ChatSession dbSession = chatSessionMapper.selectOne(
                    new LambdaQueryWrapper<ChatSession>()
                            .eq(ChatSession::getSessionId, sessionId)
            );

            int mysqlCount = dbSession != null ? dbSession.getMessageCount() : 0;
            int redisCount = redisMessages.size();

            if (redisCount <= mysqlCount) {
                // 数据已经同步，删除待重试记录
                return true;
            }

            // 同步缺失的消息
            int synced = 0;
            for (int i = mysqlCount; i < redisCount; i++) {
                RedisCacheService.Message msg = redisMessages.get(i);
                try {
                    ChatMessage chatMsg = new ChatMessage();
                    chatMsg.setSessionId(sessionId);
                    chatMsg.setRole(msg.getRole());
                    chatMsg.setContent(msg.getContent());
                    chatMessageMapper.insert(chatMsg);
                    synced++;
                } catch (Exception e) {
                    log.error("重试同步消息失败: sessionId={}, index={}", sessionId, i, e);
                }
            }

            if (synced > 0 && dbSession != null) {
                dbSession.setMessageCount(redisCount);
                chatSessionMapper.updateById(dbSession);
            }

            log.info("会话重试同步完成: sessionId={}, 同步消息数={}", sessionId, synced);
            return synced > 0;

        } catch (Exception e) {
            log.error("重试同步会话异常: sessionId={}", sessionId, e);
            return false;
        }
    }

    // ==================== 缓存击穿防护方法 ====================

    /**
     * 尝试获取缓存锁（双重检查锁模式）
     * @param lockKey 锁的 key
     * @return 是否成功获取锁
     */
    private boolean tryAcquireLock(String lockKey) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", CACHE_LOCK_TIMEOUT, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("获取缓存锁失败: {}, 继续执行", lockKey);
            return true; // 获取锁失败时允许继续执行，避免阻塞
        }
    }

    /**
     * 释放缓存锁
     */
    private void releaseLock(String lockKey) {
        try {
            stringRedisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("释放缓存锁失败: {}", lockKey);
        }
    }

    /**
     * 等待并重试获取缓存（用于未获取到锁时）
     */
    private void waitForCache(String sessionId) {
        try {
            Thread.sleep(100); // 等待100ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
