package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.entity.ChatMessage;
import org.example.entity.ChatSession;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.ChatSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息持久化服务
 * 独立事务处理，避免自调用导致事务失效
 */
@Service
public class MessagePersistService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistService.class);

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    /**
     * 持久化消息对（独立事务）
     * 保存用户消息 + AI回复，并更新会话计数
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistMessagePair(String sessionId, String userMessage, String aiReply) {
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
}
