package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈实体
 */
@Data
@TableName("user_feedback")
public class UserFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 反馈类型: chat（普通对话）/ aiops（运维分析）
     */
    private String feedbackType;

    /**
     * 评分: 1-不满意, 2-一般, 3-满意
     */
    private Integer rating;

    /**
     * 用户评论
     */
    private String userComment;

    /**
     * 用户问题
     */
    private String question;

    /**
     * AI回答
     */
    private String answer;

    /**
     * 完整上下文（JSON格式）
     * 对于 chat：包含完整对话历史
     * 对于 aiops：包含告警信息、分析步骤、最终报告
     */
    private String fullContext;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
