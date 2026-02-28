package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.ChatMessage;
import org.example.entity.ChatSummary;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.ChatSummaryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对话摘要压缩服务
 * 负责异步压缩对话历史，使用独立的压缩智能体
 * 摘要存储在独立的 chat_summary 表中
 */
@Slf4j
@Service
public class ConversationSummaryService {

    private static final String SUMMARY_LOCK_PREFIX = "lock:summary:";

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ChatSummaryMapper chatSummaryMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${conversation.summary.enabled:true}")
    private boolean summaryEnabled;

    @Value("${conversation.summary.compress-threshold:10}")
    private int compressThreshold;

    @Value("${conversation.summary.compress-batch-size:10}")
    private int compressBatchSize;

    @Value("${conversation.summary.lock-timeout-seconds:30}")
    private int lockTimeoutSeconds;

    /**
     * 触发异步压缩（带分布式锁）
     * 每5轮（10条消息）触发一次
     *
     * @param sessionId 会话ID
     */
    @Async
    public void triggerSummaryCompression(String sessionId) {
        // 检查是否启用压缩
        if (!summaryEnabled) {
            return;
        }

        String lockKey = SUMMARY_LOCK_PREFIX + sessionId;

        // 1. 尝试获取锁（会话级别）
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", lockTimeoutSeconds, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(locked)) {
            log.debug("会话 {} 正在压缩中，跳过本次触发", sessionId);
            return;
        }

        try {
            // 2. 双重检查：确认需要压缩
            Long uncompressedCount = chatMessageMapper.selectCount(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, sessionId)
                            .eq(ChatMessage::getCompressed, 0)
                            .in(ChatMessage::getRole, "user", "assistant")
            );

            if (uncompressedCount == null || uncompressedCount < compressThreshold) {
                log.debug("会话 {} 无需压缩，未压缩消息数: {}", sessionId, uncompressedCount);
                return;
            }

            // 3. 执行压缩
            doCompress(sessionId);

        } catch (Exception e) {
            log.error("压缩会话失败: {}", sessionId, e);
            // 压缩失败，下次会重试（因为 compressed 标记未更新）
        } finally {
            // 4. 释放锁
            redisTemplate.delete(lockKey);
            log.debug("释放压缩锁: {}", sessionId);
        }
    }

    /**
     * 执行压缩核心逻辑
     */
    @Transactional
    protected void doCompress(String sessionId) {
        long startTime = System.currentTimeMillis();
        log.info("开始压缩会话: {}", sessionId);

        try {
            // 1. 获取旧摘要（如果存在）
            ChatSummary oldSummary = getLatestSummary(sessionId);

            // 2. 获取未压缩的消息（按时间正序，取最早的配置数量）
            List<ChatMessage> uncompressedMessages = getUncompressedMessages(sessionId, compressBatchSize);

            if (uncompressedMessages.isEmpty()) {
                log.debug("会话 {} 没有未压缩的消息", sessionId);
                return;
            }

            // 3. 构建压缩提示词
            String compressPrompt = buildCompressPrompt(oldSummary, uncompressedMessages);
            log.debug("压缩提示词长度: {}", compressPrompt.length());

            // 4. 调用压缩智能体生成新摘要
            String newSummary = callSummaryAgent(compressPrompt);
            log.info("压缩智能体返回摘要长度: {}", newSummary.length());

            // 5. 保存新摘要（覆盖旧摘要）
            saveOrUpdateSummary(sessionId, newSummary, oldSummary, uncompressedMessages.size());

            // 6. 标记消息为已压缩
            markAsCompressed(uncompressedMessages);

            log.info("会话 {} 压缩完成，压缩消息数: {}, 耗时: {}ms",
                    sessionId, uncompressedMessages.size(), System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("执行压缩失败: sessionId={}", sessionId, e);
            throw e;
        }
    }

    /**
     * 获取会话的最新摘要
     * 由于 session_id 是唯一的，直接查询即可
     */
    private ChatSummary getLatestSummary(String sessionId) {
        return chatSummaryMapper.selectOne(
                new LambdaQueryWrapper<ChatSummary>()
                        .eq(ChatSummary::getSessionId, sessionId)
        );
    }

    /**
     * 获取未压缩的消息
     */
    private List<ChatMessage> getUncompressedMessages(String sessionId, int limit) {
        return chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getCompressed, 0)
                        .in(ChatMessage::getRole, "user", "assistant")
                        .orderByAsc(ChatMessage::getCreateTime)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 构建压缩提示词
     */
    private String buildCompressPrompt(ChatSummary oldSummary, List<ChatMessage> messages) {
        StringBuilder prompt = new StringBuilder();

        // 基础指令
        prompt.append("你是一个对话摘要专家。请将以下对话历史压缩成简洁的摘要。\n");
        prompt.append("要求：\n");
        prompt.append("1. 保留关键信息：用户的核心问题、重要的工具调用结果、关键结论\n");
        prompt.append("2. 省略冗余内容：寒暄、重复确认、中间过程\n");
        prompt.append("3. 输出格式：结构化摘要，使用中文\n");
        prompt.append("4. 摘要长度：不超过500字\n\n");

        // 旧摘要（如果有）
        if (oldSummary != null) {
            prompt.append("--- 旧摘要 ---\n");
            prompt.append(oldSummary.getContent()).append("\n");
            prompt.append("--- 旧摘要结束 ---\n\n");
        }

        // 新增对话
        prompt.append("--- 新增对话 ---\n");
        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                prompt.append("用户: ").append(msg.getContent()).append("\n");
            } else if ("assistant".equals(msg.getRole())) {
                prompt.append("助手: ").append(msg.getContent()).append("\n");
            }
        }
        prompt.append("--- 新增对话结束 ---\n\n");

        prompt.append("请基于以上信息，生成更新后的摘要（如果存在旧摘要，请合并旧摘要和新对话内容）：");

        return prompt.toString();
    }

    /**
     * 调用压缩智能体
     */
    private String callSummaryAgent(String prompt) {
        try {
            // 创建压缩专用模型
            DashScopeApi dashScopeApi = DashScopeApi.builder()
                    .apiKey(dashScopeApiKey)
                    .build();

            DashScopeChatModel summaryModel = DashScopeChatModel.builder()
                    .dashScopeApi(dashScopeApi)
                    .defaultOptions(DashScopeChatOptions.builder()
                            .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                            .withTemperature(0.1)    // 更低温度，确保稳定
                            .withMaxToken(1000)      // 控制摘要长度
                            .build())
                    .build();

            // 创建压缩专用 Agent
            ReactAgent summaryAgent = ReactAgent.builder()
                    .name("summary_agent")
                    .model(summaryModel)
                    .systemPrompt("你是对话摘要专家，请按照要求生成简洁准确的摘要。")
                    .build();

            // 执行摘要
            var response = summaryAgent.call(prompt);
            return response.getText();
        } catch (GraphRunnerException e) {
            log.error("调用压缩智能体失败: {}", e.getMessage(), e);
            throw new RuntimeException("压缩智能体调用失败", e);
        }
    }

    /**
     * 保存或更新摘要
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE 语义（先查后更新/插入）
     */
    private void saveOrUpdateSummary(String sessionId, String summaryContent,
                                     ChatSummary oldSummary, int compressedCount) {
        if (oldSummary != null) {
            // 更新现有摘要
            oldSummary.setContent(summaryContent);
            oldSummary.setVersion(oldSummary.getVersion() + 1);
            oldSummary.setCompressedCount(oldSummary.getCompressedCount() + compressedCount);
            chatSummaryMapper.updateById(oldSummary);
            log.debug("更新摘要: sessionId={}, version={}", sessionId, oldSummary.getVersion());
        } else {
            // 插入新摘要
            ChatSummary newSummary = new ChatSummary();
            newSummary.setSessionId(sessionId);
            newSummary.setContent(summaryContent);
            newSummary.setVersion(1);
            newSummary.setCompressedCount(compressedCount);
            chatSummaryMapper.insert(newSummary);
            log.debug("插入新摘要: sessionId={}", sessionId);
        }
    }

    /**
     * 标记消息为已压缩
     */
    protected void markAsCompressed(List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            msg.setCompressed(1);
            chatMessageMapper.updateById(msg);
        }
        log.debug("标记 {} 条消息为已压缩", messages.size());
    }
}
