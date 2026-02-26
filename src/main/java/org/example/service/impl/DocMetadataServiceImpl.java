package org.example.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.entity.DocMetadata;
import org.example.service.DocMetadataService;
import org.example.mapper.DocMetadataMapper;
import org.springframework.stereotype.Service;

/**
* @author 1karu32s
* @description 针对表【doc_metadata】的数据库操作Service实现
* @createDate 2026-02-26 21:37:38
*/
@Service
public class DocMetadataServiceImpl extends ServiceImpl<DocMetadataMapper, DocMetadata>
    implements DocMetadataService{

}




