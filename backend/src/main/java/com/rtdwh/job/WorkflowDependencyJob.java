package com.rtdwh.job;

import com.rtdwh.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workflow.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowDependencyJob {
    private final WorkflowService workflowService;

    @Scheduled(fixedDelayString = "${workflow.scheduler.dependency-check-ms:10000}",
            initialDelayString = "${workflow.scheduler.initial-delay-ms:10000}")
    public void promoteReadyInstances() {
        try {
            int promoted = workflowService.promoteReadyInstances();
            if (promoted > 0) log.info("Promoted {} workflow instances to queued", promoted);
        } catch (Exception exception) {
            log.error("Workflow dependency check failed", exception);
        }
    }
}
