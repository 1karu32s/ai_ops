package org.example.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一线程池配置
 * 根据任务类型（IO密集型）优化线程池参数
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Value("${thread.pool.sse.core-size:32}")
    private int sseCoreSize;

    @Value("${thread.pool.sse.max-size:64}")
    private int sseMaxSize;

    @Value("${thread.pool.sse.queue-capacity:64}")
    private int sseQueueCapacity;

    @Value("${thread.pool.vector.core-size:8}")
    private int vectorCoreSize;

    @Value("${thread.pool.vector.max-size:16}")
    private int vectorMaxSize;

    @Value("${thread.pool.vector.queue-capacity:20}")
    private int vectorQueueCapacity;

    @Value("${thread.pool.persist.core-size:32}")
    private int persistCoreSize;

    @Value("${thread.pool.persist.max-size:64}")
    private int persistMaxSize;

    @Value("${thread.pool.persist.queue-capacity:200}")
    private int persistQueueCapacity;

    /**
     * SSE 流式响应线程池
     * 特点：长时间占用线程（等待流式输出），需要较多线程
     */
    @Bean(name = "sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(sseCoreSize);
        executor.setMaxPoolSize(sseMaxSize);
        executor.setQueueCapacity(sseQueueCapacity);
        executor.setThreadNamePrefix("sse-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("SSE线程池初始化: core={}, max={}, queue={}", sseCoreSize, sseMaxSize, sseQueueCapacity);
        return executor;
    }

    /**
     * 文档向量化线程池
     * 特点：耗时操作（Milvus调用），控制并发数避免资源耗尽
     */
    @Bean(name = "vectorExecutor")
    public Executor vectorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(vectorCoreSize);
        executor.setMaxPoolSize(vectorMaxSize);
        executor.setQueueCapacity(vectorQueueCapacity);
        executor.setThreadNamePrefix("vector-");
        executor.setKeepAliveSeconds(60);
        // 拒绝时抛出异常，由调用方处理（返回降级响应）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);
        executor.initialize();
        log.info("向量化线程池初始化: core={}, max={}, queue={}", vectorCoreSize, vectorMaxSize, vectorQueueCapacity);
        return executor;
    }

    /**
     * 持久化线程池
     * 特点：高频轻量任务（数据库写入），需要较大队列缓冲
     */
    @Bean(name = "persistExecutor")
    public Executor persistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(persistCoreSize);
        executor.setMaxPoolSize(persistMaxSize);
        executor.setQueueCapacity(persistQueueCapacity);
        executor.setThreadNamePrefix("persist-");
        executor.setKeepAliveSeconds(60);
        // 队列满时由主线程执行，保证数据不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        log.info("持久化线程池初始化: core={}, max={}, queue={}", persistCoreSize, persistMaxSize, persistQueueCapacity);
        return executor;
    }
}
