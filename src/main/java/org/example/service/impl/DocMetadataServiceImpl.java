package org.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.entity.DocMetadata;
import org.example.service.DocMetadataService;
import org.example.mapper.DocMetadataMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
* @author 1karu32s
* @description 针对表【doc_metadata】的数据库操作Service实现
* @createDate 2026-02-26 21:37:38
*/
@Service
public class DocMetadataServiceImpl extends ServiceImpl<DocMetadataMapper, DocMetadata>
    implements DocMetadataService{

    @Override
    public DocMetadata getCurrentVersion(String fileName) {
        return getOne(
            new LambdaQueryWrapper<DocMetadata>()
                .eq(DocMetadata::getFileName, fileName)
                .eq(DocMetadata::getIsCurrent, true)
                .last("LIMIT 1")
        );
    }

    @Override
    public DocMetadata getByFileNameAndMd5(String fileName, String md5Hash) {
        return getOne(
            new LambdaQueryWrapper<DocMetadata>()
                .eq(DocMetadata::getFileName, fileName)
                .eq(DocMetadata::getMd5Hash, md5Hash)
                .last("LIMIT 1")
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void switchActiveVersion(String fileName, Long newVersionId) {
        // 1. 将旧版本标记为非活跃和已废弃
        update(
            new LambdaUpdateWrapper<DocMetadata>()
                .eq(DocMetadata::getFileName, fileName)
                .set(DocMetadata::getIsCurrent, false)
                .set(DocMetadata::getStatus, STATUS_DEPRECATED)
        );

        // 2. 将新版本标记为活跃和已发布
        DocMetadata newVersion = getById(newVersionId);
        if (newVersion != null) {
            newVersion.setIsCurrent(true);
            newVersion.setStatus(STATUS_PUBLISHED);
            updateById(newVersion);
        }
    }

    @Override
    public Set<Long> getActiveVersionIds() {
        List<DocMetadata> activeVersions = list(
            new LambdaQueryWrapper<DocMetadata>()
                .select(DocMetadata::getId)
                .eq(DocMetadata::getIsCurrent, true)
        );

        Set<Long> activeIds = new HashSet<>();
        for (DocMetadata version : activeVersions) {
            activeIds.add(version.getId());
        }
        return activeIds;
    }
}




