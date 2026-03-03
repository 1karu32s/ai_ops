package org.example.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户反馈请求
 */
@Data
public class FeedbackRequest {

    /**
     * 会话ID
     */
    @JsonProperty("sessionId")
    private String sessionId;

    /**
     * 反馈类型: chat/aiops
     */
    @JsonProperty("feedbackType")
    private String feedbackType;

    /**
     * 评分: 1-不满意, 2-一般, 3-满意
     */
    @JsonProperty("rating")
    private Integer rating;

    /**
     * 用户评论（可选）
     */
    @JsonProperty("userComment")
    private String userComment;

    /**
     * 用户问题
     */
    @JsonProperty("question")
    private String question;

    /**
     * AI回答
     */
    @JsonProperty("answer")
    private String answer;

    /**
     * 完整上下文（可选，如果不传则从数据库获取）
     */
    @JsonProperty("fullContext")
    private String fullContext;
}
