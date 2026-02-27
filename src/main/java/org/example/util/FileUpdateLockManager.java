package org.example.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件更新锁管理器
 * 防止同一文件被并发更新，确保版本控制的原子性
 * 参考 ChatController.SessionInfo 的设计
 */
@Component
public class FileUpdateLockManager {

    private static final Logger logger = LoggerFactory.getLogger(FileUpdateLockManager.class);

    // 每个文件名对应一把锁
    private final Map<String, FileLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * 获取文件锁并加锁
     *
     * @param fileName 文件名
     * @return FileLock 对象，使用完后需调用 unlock() 释放
     */
    public FileLock acquireLock(String fileName) {
        logger.debug("获取文件锁: {}", fileName);
        return fileLocks.computeIfAbsent(fileName, FileLock::new).lock();
    }

    /**
     * 释放文件锁
     *
     * @param fileName 文件名
     */
    public void releaseLock(String fileName) {
        logger.debug("释放文件锁: {}", fileName);
        FileLock lock = fileLocks.get(fileName);
        if (lock != null) {
            lock.unlock();
        }
    }

    /**
     * 清理未使用的锁（可选，定期调用以释放内存）
     */
    public void cleanupIdleLocks() {
        fileLocks.entrySet().removeIf(entry -> {
            FileLock lock = entry.getValue();
            if (!lock.isLocked()) {
                logger.debug("清理空闲锁: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 文件锁
     */
    public static class FileLock {
        private final String fileName;
        private final ReentrantLock lock;

        public FileLock(String fileName) {
            this.fileName = fileName;
            this.lock = new ReentrantLock();
        }

        /**
         * 加锁
         *
         * @return this，支持链式调用
         */
        public FileLock lock() {
            lock.lock();
            return this;
        }

        /**
         * 解锁
         */
        public void unlock() {
            // 只有持有锁的线程才能解锁，避免异常
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        /**
         * 是否已被加锁
         */
        public boolean isLocked() {
            return lock.isLocked();
        }

        public String getFileName() {
            return fileName;
        }
    }
}
