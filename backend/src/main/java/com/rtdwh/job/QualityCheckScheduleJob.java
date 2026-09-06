package com.rtdwh.job;

import com.rtdwh.service.QualityCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quality.schedule", name = "enabled", havingValue = "true")
public class QualityCheckScheduleJob {
    private final QualityCheckService qualityCheckService;

    @Scheduled(fixedDelayString = "${quality.schedule.interval-ms:3600000}",
            initialDelayString = "${quality.schedule.initial-delay-ms:60000}")
    public void run() {
        try {
            int abnormal = qualityCheckService.runAllChecks("scheduled");
            log.info("Scheduled quality check completed: abnormal={}", abnormal);
        } catch (Exception exception) {
            log.error("Scheduled quality check failed", exception);
        }
    }
}
