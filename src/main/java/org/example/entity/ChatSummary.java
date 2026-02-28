package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 对话摘要实体
 * @author 1karu32s
 * @description 对话摘要表，与会话一对一
 * @createDate 2026-02-28
 */
@TableName("chat_summary")
@Data
public class ChatSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID（唯一）
     */
    private String sessionId;

    /**
     * 摘要内容
     */
    private String content;

    /**
     * 摘要版本号，每次压缩递增
     */
    private Integer version;

    /**
     * 已压缩的消息条数
     */
    private Integer compressedCount;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
