package org.example.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 文件更新锁管理器
 * 防止同一文件被并发更新，确保版本控制的原子性
 * 使用 Redis 分布式锁，支持集群部署
 * 锁的 key 基于文件名，确保同名文件的向量化串行执行
 */
@Component
public class FileUpdateLockManager {

    private static final Logger logger = LoggerFactory.getLogger(FileUpdateLockManager.class);

    private static final String LOCK_PREFIX = "lock:file:";

    @Value("${conversation.summary.lock-timeout-seconds:30}")
    private int lockTimeoutSeconds;

    private final StringRedisTemplate stringRedisTemplate;

    public FileUpdateLockManager(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取文件锁并加锁
     * 使用文件名作为锁 key，确保同名文件的向量化串行执行
     *
     * @param fileName 文件名
     * @return 是否获取锁成功
     */
    public boolean acquireLock(String fileName) {
        String lockKey = LOCK_PREFIX + fileName;

        logger.debug("获取文件锁: fileName={}, lockKey={}", fileName, lockKey);

        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", lockTimeoutSeconds, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 释放文件锁
     *
     * @param fileName 文件名
     */
    public void releaseLock(String fileName) {
        String lockKey = LOCK_PREFIX + fileName;

        logger.debug("释放文件锁: fileName={}, lockKey={}", fileName, lockKey);
        stringRedisTemplate.delete(lockKey);
    }

}
