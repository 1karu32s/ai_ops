package org.example.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池监控服务
 * 定期收集线程池指标，支持告警
 */
@Slf4j
@Service
public class ThreadPoolMonitorService {

    @Autowired
    @Qualifier("sseExecutor")
    private Executor sseExecutor;

    @Autowired
    @Qualifier("vectorExecutor")
    private Executor vectorExecutor;

    @Autowired
    @Qualifier("persistExecutor")
    private Executor persistExecutor;

    @Autowired
    @Qualifier("summaryExecutor")
    private Executor summaryExecutor;

    // 告警阈值
    private static final double QUEUE_USAGE_WARN_THRESHOLD = 0.7;   // 队列使用率告警阈值
    private static final double QUEUE_USAGE_ERROR_THRESHOLD = 0.9;  // 队列使用率严重告警阈值

    // 统计数据
    private final AtomicLong lastWarnTime = new AtomicLong(0);
    private static final long WARN_INTERVAL_MS = 60000; // 告警间隔 60 秒

    /**
     * 每 30 秒打印一次线程池状态
     */
    @Scheduled(fixedRate = 30000)
    public void logThreadPoolStatus() {
        log.info("========== 线程池状态监控 ==========");

        logPoolStatus("SSE", sseExecutor);
        logPoolStatus("Vector", vectorExecutor);
        logPoolStatus("Persist", persistExecutor);
        logPoolStatus("Summary", summaryExecutor);

        log.info("==================================");
    }

    /**
     * 获取所有线程池指标（用于监控接口）
     */
    public List<ThreadPoolMetrics> getAllThreadPoolMetrics() {
        return List.of(
            getMetrics("SSE", sseExecutor),
            getMetrics("Vector", vectorExecutor),
            getMetrics("Persist", persistExecutor),
            getMetrics("Summary", summaryExecutor)
        );
    }

    /**
     * 记录单个线程池状态
     */
    private void logPoolStatus(String name, Executor executor) {
        try {
            ThreadPoolMetrics metrics = getMetrics(name, executor);
            double queueUsage = metrics.getQueueUsage();

            log.info("[{}] 活跃线程: {}/{}, 队列大小: {}/{}, 队列使用率: {:.1f}%, 完成任务: {}",
                    name,
                    metrics.getActiveCount(), metrics.getPoolSize(),
                    metrics.getQueueSize(), metrics.getQueueCapacity(),
                    queueUsage * 100,
                    metrics.getCompletedTaskCount());

            // 队列使用率告警
            if (queueUsage >= QUEUE_USAGE_ERROR_THRESHOLD) {
                long now = System.currentTimeMillis();
                if (now - lastWarnTime.get() > WARN_INTERVAL_MS) {
                    log.error("【线程池告警】[{}] 队列使用率过高: {:.1f}%, 请关注系统负载！",
                            name, queueUsage * 100);
                    lastWarnTime.set(now);
                }
            } else if (queueUsage >= QUEUE_USAGE_WARN_THRESHOLD) {
                long now = System.currentTimeMillis();
                if (now - lastWarnTime.get() > WARN_INTERVAL_MS) {
                    log.warn("【线程池警告】[{}] 队列使用率: {:.1f}%, 建议关注",
                            name, queueUsage * 100);
                    lastWarnTime.set(now);
                }
            }
        } catch (Exception e) {
            log.error("获取线程池状态失败: {}", name, e);
        }
    }

    /**
     * 获取线程池指标
     */
    private ThreadPoolMetrics getMetrics(String name, Executor executor) {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics();
        metrics.setName(name);

        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            ThreadPoolExecutor threadPool = taskExecutor.getThreadPoolExecutor();
            if (threadPool != null) {
                metrics.setPoolSize(threadPool.getPoolSize());
                metrics.setActiveCount(threadPool.getActiveCount());
                metrics.setCorePoolSize(threadPool.getCorePoolSize());
                metrics.setMaximumPoolSize(threadPool.getMaximumPoolSize());
                metrics.setQueueSize(threadPool.getQueue().size());
                metrics.setQueueCapacity(getQueueCapacity(threadPool));
                metrics.setCompletedTaskCount(threadPool.getCompletedTaskCount());
                metrics.setLargestPoolSize(threadPool.getLargestPoolSize());
                metrics.setTaskCount(threadPool.getTaskCount());
            }
        } else if (executor instanceof ThreadPoolExecutor threadPool) {
            metrics.setPoolSize(threadPool.getPoolSize());
            metrics.setActiveCount(threadPool.getActiveCount());
            metrics.setCorePoolSize(threadPool.getCorePoolSize());
            metrics.setMaximumPoolSize(threadPool.getMaximumPoolSize());
            metrics.setQueueSize(threadPool.getQueue().size());
            metrics.setQueueCapacity(getQueueCapacity(threadPool));
            metrics.setCompletedTaskCount(threadPool.getCompletedTaskCount());
            metrics.setLargestPoolSize(threadPool.getLargestPoolSize());
            metrics.setTaskCount(threadPool.getTaskCount());
        }

        return metrics;
    }

    /**
     * 获取队列容量（通过反射）
     */
    private int getQueueCapacity(ThreadPoolExecutor threadPool) {
        try {
            BlockingQueue<Runnable> queue = threadPool.getQueue();
            // ThreadPoolTaskExecutor 使用 LinkedBlockingQueue，有 capacity 字段
            if (queue.getClass().getName().contains("LinkedBlockingQueue")) {
                Field field = queue.getClass().getDeclaredField("capacity");
                field.setAccessible(true);
                int capacity = (int) field.get(queue);
                return capacity == Integer.MAX_VALUE ? -1 : capacity;
            }
        } catch (Exception e) {
            // 忽略
        }
        return -1;
    }

    /**
     * 线程池指标数据结构
     */
    @Data
    public static class ThreadPoolMetrics {
        private String name;
        private int poolSize;           // 当前线程池大小
        private int activeCount;        // 活跃线程数
        private int corePoolSize;       // 核心线程数
        private int maximumPoolSize;    // 最大线程数
        private int queueSize;          // 队列当前大小
        private int queueCapacity;      // 队列容量 (-1 表示无界)
        private long completedTaskCount; // 已完成任务数
        private int largestPoolSize;    // 历史最大线程数
        private long taskCount;         // 总任务数

        public double getQueueUsage() {
            if (queueCapacity <= 0) return 0;
            return (double) queueSize / queueCapacity;
        }
    }
}
