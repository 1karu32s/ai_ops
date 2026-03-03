package org.example.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.FeedbackRequest;
import org.example.entity.ChatMessage;
import org.example.entity.UserFeedback;
import org.example.mapper.ChatMessageMapper;
import org.example.mapper.UserFeedbackMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈服务
 */
@Slf4j
@Service
public class UserFeedbackService {

    @Autowired
    private UserFeedbackMapper userFeedbackMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ConversationService conversationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存用户反馈
     *
     * @param request 反馈请求
     * @return 是否保存成功
     */
    @Transactional
    public boolean saveFeedback(FeedbackRequest request) {
        try {
            UserFeedback feedback = new UserFeedback();

            // 基本信息
            feedback.setSessionId(request.getSessionId());
            feedback.setFeedbackType(request.getFeedbackType());
            feedback.setRating(request.getRating());
            feedback.setUserComment(request.getUserComment());
            feedback.setQuestion(request.getQuestion());
            feedback.setAnswer(request.getAnswer());

            // 如果前端没有传 fullContext，自动构建
            if (request.getFullContext() == null || request.getFullContext().isEmpty()) {
                String fullContext = buildFullContext(request);
                feedback.setFullContext(fullContext);
            } else {
                feedback.setFullContext(request.getFullContext());
            }

            // 保存到数据库
            userFeedbackMapper.insert(feedback);
            log.info("用户反馈已保存 - sessionId: {}, type: {}, rating: {}",
                request.getSessionId(), request.getFeedbackType(), request.getRating());

            return true;

        } catch (Exception e) {
            log.error("保存用户反馈失败 - sessionId: {}", request.getSessionId(), e);
            throw new RuntimeException("保存反馈失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建完整上下文
     * 对于 chat：获取完整对话历史
     * 对于 aiops：构建分析链路信息
     */
    private String buildFullContext(FeedbackRequest request) {
        try {
            Map<String, Object> context = new HashMap<>();

            if ("chat".equals(request.getFeedbackType())) {
                // 获取完整对话历史
                List<ChatMessage> messages = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, request.getSessionId())
                        .orderByAsc(ChatMessage::getCreateTime)
                );

                context.put("messages", messages);
                context.put("messageCount", messages.size());

            } else if ("aiops".equals(request.getFeedbackType())) {
                // AIOps 分析上下文
                context.put("question", request.getQuestion());
                context.put("answer", request.getAnswer());
                context.put("analysisType", "aiops");
                // 可以添加更多 AIOps 特定的上下文信息
            }

            return objectMapper.writeValueAsString(context);

        } catch (Exception e) {
            log.error("构建完整上下文失败", e);
            return "{\"error\": \"构建上下文失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取反馈统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getFeedbackStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // 总反馈数
        Long total = userFeedbackMapper.selectCount(null);
        stats.put("total", total);

        // 按评分统计
        Map<String, Long> ratingStats = new HashMap<>();
        for (int rating = 1; rating <= 3; rating++) {
            Long count = userFeedbackMapper.selectCount(
                new LambdaQueryWrapper<UserFeedback>()
                    .eq(UserFeedback::getRating, rating)
            );
            ratingStats.put("rating_" + rating, count);
        }
        stats.put("ratingStats", ratingStats);

        // 按类型统计
        Long chatCount = userFeedbackMapper.selectCount(
            new LambdaQueryWrapper<UserFeedback>()
                .eq(UserFeedback::getFeedbackType, "chat")
        );
        Long aiopsCount = userFeedbackMapper.selectCount(
            new LambdaQueryWrapper<UserFeedback>()
                .eq(UserFeedback::getFeedbackType, "aiops")
        );
        stats.put("chatCount", chatCount);
        stats.put("aiopsCount", aiopsCount);

        // 满意率（评分3的比例）
        Long satisfiedCount = userFeedbackMapper.selectCount(
            new LambdaQueryWrapper<UserFeedback>()
                .eq(UserFeedback::getRating, 3)
        );
        double satisfactionRate = total > 0 ? (satisfiedCount * 100.0 / total) : 0;
        stats.put("satisfactionRate", String.format("%.2f%%", satisfactionRate));

        return stats;
    }

    /**
     * 获取最近的负面反馈（用于改进分析）
     *
     * @param limit 返回数量
     * @return 负面反馈列表
     */
    public List<UserFeedback> getRecentNegativeFeedback(int limit) {
        return userFeedbackMapper.selectList(
            new LambdaQueryWrapper<UserFeedback>()
                .eq(UserFeedback::getRating, 1)
                .orderByDesc(UserFeedback::getCreateTime)
                .last("LIMIT " + limit)
        );
    }
}
