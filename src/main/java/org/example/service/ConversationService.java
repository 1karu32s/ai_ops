package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.ChatMessage;
import org.example.entity.ChatSession;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.ChatSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

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
    @Qualifier("persistExecutor")
    private Executor persistExecutor;

    /**
     * 获取或创建会话
     */
    public String getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 检查 Redis 中是否存在
        RedisCacheService.SessionMetadata metadata = redisCacheService.getSession(sessionId);

        if (metadata == null) {
            // Redis 未命中，检查 MySQL
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
                    log.debug("更新会话标题: sessionId={}, title={}", sessionId, newTitle);
                }
            }
        } catch (Exception e) {
            log.warn("更新会话标题失败: sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取会话消息列表
     * Cache-Aside 模式
     */
    public List<RedisCacheService.Message> getMessages(String sessionId, int limit) {
        // 1. 先查 Redis
        List<RedisCacheService.Message> messages = redisCacheService.getMessages(sessionId, limit);

        if (!messages.isEmpty()) {
            return messages;
        }

        // 2. Redis 未命中，查 MySQL
        List<ChatMessage> dbMessages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime)
                        .last("LIMIT " + limit)
        );

        if (!dbMessages.isEmpty()) {
            // 3. 回填 Redis
            for (ChatMessage msg : dbMessages) {
                redisCacheService.addMessage(sessionId, msg.getRole(), msg.getContent());
            }
            log.debug("从 MySQL 回填消息到 Redis: sessionId={}, count={}", sessionId, dbMessages.size());
        }

        // 4. 转换为 DTO
        return dbMessages.stream().map(msg -> {
            RedisCacheService.Message m = new RedisCacheService.Message();
            m.setRole(msg.getRole());
            m.setContent(msg.getContent());
            m.setCreateTime(msg.getCreateTime().getTime());
            return m;
        }).toList();
    }

    /**
     * 获取会话元数据
     */
    public RedisCacheService.SessionMetadata getSessionMetadata(String sessionId) {
        // 1. 先查 Redis
        RedisCacheService.SessionMetadata metadata = redisCacheService.getSession(sessionId);

        if (metadata != null) {
            return metadata;
        }

        // 2. Redis 未命中，查 MySQL
        ChatSession dbSession = chatSessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getSessionId, sessionId)
        );

        if (dbSession != null) {
            // 回填 Redis
            redisCacheService.createSession(sessionId, dbSession.getTitle());
            metadata = new RedisCacheService.SessionMetadata();
            metadata.setSessionId(dbSession.getSessionId());
            metadata.setTitle(dbSession.getTitle());
            metadata.setMessageCount(dbSession.getMessageCount());
            metadata.setCreateTime(dbSession.getCreateTime().getTime());
            metadata.setUpdateTime(dbSession.getUpdateTime().getTime());
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
     * 修改会话标题（同步写穿：先MySQL后Redis）
     */
    public void updateSessionTitle(String sessionId, String newTitle) {
        // 1. 先更新 MySQL（主数据源）
        ChatSession session = chatSessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getSessionId, sessionId)
        );
        if (session == null) {
            throw new RuntimeException("会话不存在: " + sessionId);
        }

        String oldTitle = session.getTitle();
        session.setTitle(newTitle);
        chatSessionMapper.updateById(session);
        log.debug("MySQL会话标题已更新: sessionId={}, oldTitle={}, newTitle={}", sessionId, oldTitle, newTitle);

        // 2. 再更新 Redis（缓存），如果失败则回滚MySQL
        try {
            redisCacheService.updateSessionTitle(sessionId, newTitle);
            log.debug("Redis会话标题已更新: sessionId={}, newTitle={}", sessionId, newTitle);
        } catch (Exception e) {
            // 回滚 MySQL
            session.setTitle(oldTitle);
            chatSessionMapper.updateById(session);
            log.error("Redis更新失败，已回滚MySQL: sessionId={}", sessionId, e);
            throw new RuntimeException("缓存更新失败，已回滚: " + e.getMessage());
        }
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
     * 带重试机制的持久化
     * 指数退避重试，最大重试3次
     */
    private void persistWithRetry(String sessionId, String userMessage, String aiReply, long startTime) {
        int maxRetries = 3;
        int retryCount = 0;
        boolean success = false;

        while (retryCount < maxRetries && !success) {
            try {
                persistMessagePair(sessionId, userMessage, aiReply);
                success = true;
                long dbTime = System.currentTimeMillis();
                log.debug("MySQL 异步写入完成，总耗时: {}ms", dbTime - startTime);

                // 持久化成功后，检查是否需要触发压缩
                conversationSummaryService.triggerSummaryCompression(sessionId);

            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    // 达到最大重试次数
                    log.error("异步持久化消息失败，已达最大重试次数({}): sessionId={}, error={}",
                            maxRetries, sessionId, e.getMessage(), e);
                    // 失败的消息可以记录到本地队列或死信队列，这里暂时只记录日志
                    return;
                }

                // 判断是否可重试的异常类型
                boolean isRetryable = isRetryableException(e);
                if (!isRetryable) {
                    log.error("异步持久化消息失败，不可重试异常: sessionId={}, error={}",
                            sessionId, e.getMessage(), e);
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
                    return;
                }
            }
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
     * 持久化消息对到 MySQL
     */
    @Transactional
    protected void persistMessagePair(String sessionId, String userMessage, String aiReply) {
        try {
            // 保存用户消息
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(sessionId);
            userMsg.setRole("user");
            userMsg.setContent(userMessage);
            chatMessageMapper.insert(userMsg);

            // 保存 AI 回复
            ChatMessage aiMsg = new ChatMessage();
            aiMsg.setSessionId(sessionId);
            aiMsg.setRole("assistant");
            aiMsg.setContent(aiReply);
            chatMessageMapper.insert(aiMsg);

            // 更新会话消息计数
            ChatSession session = chatSessionMapper.selectOne(
                    new LambdaQueryWrapper<ChatSession>()
                            .eq(ChatSession::getSessionId, sessionId)
            );
            if (session != null) {
                session.setMessageCount(session.getMessageCount() + 1);
                chatSessionMapper.updateById(session);
            }

            log.debug("消息对已持久化到 MySQL: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("持久化消息对失败: sessionId={}", sessionId, e);
            throw e;
        }
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
}
