package org.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.entity.ChatSummary;

/**
 * 对话摘要 Mapper
 * @author 1karu32s
 * @description 针对表【chat_summary】的数据库操作Mapper
 * @createDate 2026-02-28
 * @Entity org.example.entity.ChatSummary
 */
@Mapper
public interface ChatSummaryMapper extends BaseMapper<ChatSummary> {

}
