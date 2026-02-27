package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.entity.ChatSession;

/**
 * 对话会话 Mapper
 * @author 1karu32s
 * @description 针对表【chat_session】的数据库操作Mapper
 * @createDate 2026-02-27
 * @Entity org.example.entity.ChatSession
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

}
