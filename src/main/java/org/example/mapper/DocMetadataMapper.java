package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.entity.DocMetadata;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
* @author 1karu32s
* @description 针对表【doc_metadata】的数据库操作Mapper
* @createDate 2026-02-26 21:37:38
* @Entity org.example.entity.DocMetadata
*/
@Mapper
public interface DocMetadataMapper extends BaseMapper<DocMetadata> {

}




