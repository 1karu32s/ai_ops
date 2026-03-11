package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.entity.DocMetadata;
import org.example.service.DocMetadataService;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private DocMetadataService docMetadataService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("vectorExecutor")
    private Executor vectorExecutor;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        // 1. 格式校验
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        // 2. 保存文件到磁盘
        Path filePath;
        String md5Hash;
        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            filePath = uploadDir.resolve(originalFilename).normalize();

            // 如果文件已存在，先删除旧文件（覆盖磁盘文件）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }

            // 保存文件到磁盘
            Files.copy(file.getInputStream(), filePath);
            logger.info("文件上传成功: {}", filePath);

            // 计算 MD5
            md5Hash = calculateMd5(filePath);
            logger.info("文件 MD5: {}", md5Hash);

        } catch (IOException e) {
            logger.error("文件上传失败: {}", e.getMessage(), e);
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }

        // 3. 检查数据库是否已存在相同内容的版本（防止唯一索引冲突）
        DocMetadata existing = docMetadataService.getByFileNameAndMd5(originalFilename, md5Hash);
        if (existing != null) {
            logger.info("文件内容已存在: fileName={}, md5={}", originalFilename, md5Hash);
            // 删除刚上传的冗余文件
            try {
                Files.deleteIfExists(filePath);
                logger.info("已删除冗余文件: {}", filePath);
            } catch (IOException ex) {
                logger.warn("删除冗余文件失败: {}", filePath, ex);
            }
            return ResponseEntity.ok(createResponse("文件内容未变化，跳过处理", originalFilename, Path.of(filePath.toString()), file.getSize()));
        }

        // 4. 创建版本记录（status=publishing, is_current=false）
        DocMetadata newVersion = new DocMetadata();
        newVersion.setFileName(originalFilename);
        newVersion.setFilePath(filePath.toString());
        newVersion.setMd5Hash(md5Hash);
        newVersion.setStatus(DocMetadataService.STATUS_PUBLISHING);
        newVersion.setIsCurrent(false);
        newVersion.setFileSize(file.getSize());
        docMetadataService.save(newVersion);

        logger.info("创建新版本记录: id={}, file={}", newVersion.getId(), originalFilename);

        // 5. 异步处理向量化（向量化阶段会做内容重复检查和加锁）
        Long versionId = newVersion.getId();
        vectorExecutor.execute(() -> {
            try {
                vectorIndexService.indexSingleFileWithVersion(filePath.toString(), versionId);
            } catch (Exception ex) {
                logger.error("异步向量化失败: file={}, versionId={}, error={}",
                    originalFilename, versionId, ex.getMessage(), ex);
                // 失败时标记版本为 deprecated
                newVersion.setStatus(DocMetadataService.STATUS_DEPRECATED);
                docMetadataService.updateById(newVersion);
            }
        });

        // 6. 立即返回响应
        return ResponseEntity.ok(createResponse(
            "文件已接收，正在后台处理向量化",
            originalFilename, filePath, file.getSize()));
    }

    /**
     * 计算 MD5 哈希
     */
    private String calculateMd5(Path filePath) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }

        try (DigestInputStream dis = new DigestInputStream(Files.newInputStream(filePath), md)) {
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1) {
                // 读取文件以更新摘要
            }
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 创建统一响应
     */
    private ApiResponse<FileUploadRes> createResponse(String message, String fileName, Path filePath, long fileSize) {
        FileUploadRes data = new FileUploadRes(fileName, filePath.toString(), fileSize);
        ApiResponse<FileUploadRes> response = new ApiResponse<>();
        response.setCode(200);
        response.setMessage(message);
        response.setData(data);
        return response;
    }

    /**
     * 统一 API 响应格式
     */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}
