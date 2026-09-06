package com.rtdwh.job;

import com.rtdwh.service.AlertEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "alert.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AlertEvaluationJob {
    private final AlertEvaluationService evaluationService;

    @Scheduled(fixedDelayString = "${alert.schedule.interval-ms:30000}",
            initialDelayString = "${alert.schedule.initial-delay-ms:15000}")
    public void evaluate() {
        try {
            AlertEvaluationService.EvaluationSummary summary = evaluationService.evaluateAll();
            log.debug("Alert evaluation completed: evaluated={}, triggered={}, recovered={}",
                    summary.evaluated(), summary.triggered(), summary.recovered());
        } catch (Exception exception) {
            log.error("Scheduled alert evaluation failed", exception);
        }
    }
}
