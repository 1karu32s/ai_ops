package org.example.service;

import org.example.entity.DocMetadata;
import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author 1karu32s
* @description 针对表【doc_metadata】的数据库操作Service
* @createDate 2026-02-26 21:37:38
*/
public interface DocMetadataService extends IService<DocMetadata> {

    /**
     * 获取文件的当前活跃版本
     *
     * @param fileName 文件名
     * @return 当前活跃版本，不存在则返回 null
     */
    DocMetadata getCurrentVersion(String fileName);

    /**
     * 根据文件名和 MD5 查找版本
     *
     * @param fileName 文件名
     * @param md5Hash MD5 哈希
     * @return 版本记录，不存在则返回 null
     */
    DocMetadata getByFileNameAndMd5(String fileName, String md5Hash);

    /**
     * 切换文件的活跃版本（原子操作）
     * 1. 将旧版本标记为非活跃 (is_current=false, status=2)
     * 2. 将新版本标记为活跃 (is_current=true, status=1)
     *
     * @param fileName 文件名
     * @param newVersionId 新版本 ID
     */
    void switchActiveVersion(String fileName, Long newVersionId);

    /**
     * 获取所有活跃版本的 ID 列表
     *
     * @return 活跃版本的 ID 集合
     */
    java.util.Set<Long> getActiveVersionIds();

    /**
     * 状态常量
     */
    int STATUS_PUBLISHING = 0;  // 发布中
    int STATUS_PUBLISHED = 1;   // 已发布
    int STATUS_DEPRECATED = 2;  // 已废弃
}
