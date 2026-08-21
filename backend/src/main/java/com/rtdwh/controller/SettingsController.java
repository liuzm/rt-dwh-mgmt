package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.service.SystemHealthStatusService;
import com.rtdwh.service.SystemSettingService;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SystemHealthStatusService systemHealthStatusService;
    private final SystemSettingService systemSettingService;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping("/flink-cluster")
    public ApiResponse<Map<String, Object>> getFlinkClusterConfig() {
        return ApiResponse.success(systemSettingService.getFlinkConfig());
    }

    @PutMapping("/flink-cluster")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> updateFlinkClusterConfig(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(
                    "配置已保存并立即生效",
                    systemSettingService.updateFlinkConfig(body, securityContextUtil.getCurrentUsername())
            );
        } catch (IllegalArgumentException exception) {
            return ApiResponse.error(400, exception.getMessage());
        }
    }

    @PostMapping("/flink-cluster/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> testFlinkClusterConfig(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.success(systemSettingService.testFlinkConfig(body));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.error(400, exception.getMessage());
        }
    }

    @GetMapping("/health-check")
    public ApiResponse<Map<String, Object>> healthCheck() {
        return ApiResponse.success(systemHealthStatusService.refreshAll("manual"));
    }

    @GetMapping("/health-check/{component}")
    public ApiResponse<Map<String, Object>> healthCheckComponent(@PathVariable String component) {
        try {
            Map<String, Object> status = systemHealthStatusService.refreshComponent(component, "manual");
            @SuppressWarnings("unchecked")
            Map<String, Object> componentStatus = (Map<String, Object>) status.get(component.toLowerCase(Locale.ROOT));
            return ApiResponse.success(componentStatus);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.error(400, exception.getMessage());
        }
    }

    /** Fast read used on page load; never probes Flink/Paimon/MySQL. */
    @GetMapping("/health-status")
    public ApiResponse<Map<String, Object>> getHealthStatus() {
        return ApiResponse.success(systemHealthStatusService.getLatest());
    }

    @PostMapping("/health-status/refresh")
    public ApiResponse<Map<String, Object>> refreshHealthStatus() {
        return ApiResponse.success(systemHealthStatusService.refreshAll("manual"));
    }

    @PostMapping("/health-status/{component}/refresh")
    public ApiResponse<Map<String, Object>> refreshHealthComponent(@PathVariable String component) {
        try {
            return ApiResponse.success(systemHealthStatusService.refreshComponent(component, "manual"));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.error(400, exception.getMessage());
        }
    }
}
