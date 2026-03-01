package org.example.controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.service.ThreadPoolMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 监控接口
 * 提供线程池状态查询
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    @Autowired
    private ThreadPoolMonitorService threadPoolMonitorService;

    /**
     * 获取所有线程池状态
     */
    @GetMapping("/thread-pools")
    public ResponseEntity<ApiResponse<List<ThreadPoolMonitorService.ThreadPoolMetrics>>> getThreadPoolStatus() {
        List<ThreadPoolMonitorService.ThreadPoolMetrics> metrics = threadPoolMonitorService.getAllThreadPoolMetrics();
        return ResponseEntity.ok(ApiResponse.success(metrics));
    }

    @Data
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(200, "success", data);
        }
    }
}
