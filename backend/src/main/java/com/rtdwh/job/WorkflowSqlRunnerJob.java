package com.rtdwh.job;

import com.rtdwh.service.WorkflowSqlRunnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workflow.runner", name = "enabled", havingValue = "true")
public class WorkflowSqlRunnerJob {
    private final WorkflowSqlRunnerService runnerService;

    @Scheduled(fixedDelayString = "${workflow.runner.interval-ms:5000}",
            initialDelayString = "${workflow.runner.initial-delay-ms:15000}")
    public void runCycle() {
        try {
            WorkflowSqlRunnerService.RunCycleSummary summary = runnerService.runCycle();
            if (summary.submitted() + summary.completed() + summary.retried() + summary.recovered() > 0) {
                log.info("Workflow SQL runner cycle: {}", summary);
            }
        } catch (Exception exception) {
            log.error("Workflow SQL runner cycle failed", exception);
        }
    }
}
