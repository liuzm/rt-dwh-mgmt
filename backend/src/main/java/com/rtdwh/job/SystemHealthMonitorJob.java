package com.rtdwh.job;

import com.rtdwh.service.SystemHealthStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "health.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SystemHealthMonitorJob {

    private final SystemHealthStatusService systemHealthStatusService;

    @Scheduled(
            fixedDelayString = "${health.monitor.interval-ms:60000}",
            initialDelayString = "${health.monitor.initial-delay-ms:5000}"
    )
    public void refreshHealthStatus() {
        try {
            MapSummary summary = MapSummary.from(systemHealthStatusService.refreshAll("scheduled"));
            log.debug("Scheduled system health check completed: status={}, durationMs={}",
                    summary.overall(), summary.durationMs());
        } catch (Exception exception) {
            log.error("Scheduled system health check failed", exception);
        }
    }

    private record MapSummary(String overall, Object durationMs) {
        private static MapSummary from(java.util.Map<String, Object> result) {
            return new MapSummary(String.valueOf(result.getOrDefault("overall", "unknown")), result.get("durationMs"));
        }
    }
}
