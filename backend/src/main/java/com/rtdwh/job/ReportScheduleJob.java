package com.rtdwh.job;

import com.rtdwh.service.ReportScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "report.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportScheduleJob {
    private final ReportScheduleService reportScheduleService;

    @Scheduled(fixedDelayString = "${report.schedule.interval-ms:30000}",
            initialDelayString = "${report.schedule.initial-delay-ms:30000}")
    public void runDueReports() {
        try {
            int executed = reportScheduleService.runDueReports();
            if (executed > 0) log.info("Executed {} scheduled reports", executed);
        } catch (Exception exception) {
            log.error("Scheduled report execution failed", exception);
        }
    }
}
