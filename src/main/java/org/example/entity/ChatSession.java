package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 对话会话实体
 * @author 1karu32s
 * @description 对话会话表
 * @createDate 2026-02-27
 */
@TableName("chat_session")
@Data
public class ChatSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID (UUID)
     */
    private String sessionId;

    /**
     * 会话标题（从首条用户消息提取）
     */
    private String title;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 消息数量（成对计数：用户消息+AI回复）
     */
    private Integer messageCount;

    /**
     * 是否已删除（软删除）
     */
    private Boolean deleted;
}
