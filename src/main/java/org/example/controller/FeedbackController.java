package org.example.controller;

import lombok.Data;
import lombok.AllArgsConstructor;
import org.example.dto.FeedbackRequest;
import org.example.entity.UserFeedback;
import org.example.service.UserFeedbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户反馈接口
 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackController.class);

    @Autowired
    private UserFeedbackService userFeedbackService;

    /**
     * 简单反馈接口 - 记录不满意的回复
     * 前端直接调用此接口，参数简化
     */
    @PostMapping("/feedback")
    public ResponseEntity<ApiResponse<String>> submitFeedback(@RequestBody Map<String, Object> request) {
        try {
            String sessionId = (String) request.get("sessionId");
            String answer = (String) request.get("answer");
            String feedback = (String) request.get("feedback");

            logger.info("收到反馈 - sessionId: {}, feedback: {}, answer长度: {}",
                sessionId, feedback, answer != null ? answer.length() : 0);

            // 参数校验
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            if (answer == null || answer.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("回复内容不能为空"));
            }

            // 转换为 FeedbackRequest 并保存
            FeedbackRequest feedbackRequest = new FeedbackRequest();
            feedbackRequest.setSessionId(sessionId);
            feedbackRequest.setAnswer(answer);
            feedbackRequest.setFeedbackType("chat");  // 默认为 chat 类型
            feedbackRequest.setRating("dislike".equals(feedback) ? 1 : 2);  // dislike = 1(不满意), 其他 = 2(一般)
            feedbackRequest.setQuestion("");  // 简单模式不需要问题

            boolean success = userFeedbackService.saveFeedback(feedbackRequest);

            if (success) {
                return ResponseEntity.ok(ApiResponse.success("感谢您的反馈，我们会继续改进！"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("反馈提交失败"));
            }

        } catch (Exception e) {
            logger.error("提交反馈失败", e);
            return ResponseEntity.ok(ApiResponse.error("反馈提交失败: " + e.getMessage()));
        }
    }

    /**
     * 统一响应格式
     */
    @Data
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(200, "success", data);
        }

        public static <T> ApiResponse<T> success(String message, T data) {
            return new ApiResponse<>(200, message, data);
        }

        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(500, message, null);
        }
    }
}
