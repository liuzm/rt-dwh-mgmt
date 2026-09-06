package com.rtdwh.service;

import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import com.rtdwh.service.AlertEvaluationPersistenceService.Condition;
import com.rtdwh.service.AlertEvaluationPersistenceService.TransitionResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {
    private final AlertRuleRepository ruleRepository;
    private final SyncTaskRepository taskRepository;
    private final QualityAlertRepository qualityAlertRepository;
    private final AlertNotifyService notifyService;
    private final AlertEvaluationPersistenceService persistenceService;

    public EvaluationSummary evaluateAll() {
        int evaluated = 0;
        int triggered = 0;
        int recovered = 0;
        for (AlertRule rule : ruleRepository.findByEnabledTrueOrderByIdAsc()) {
            try {
                EvaluationSummary result = evaluate(rule);
                evaluated++;
                triggered += result.triggered();
                recovered += result.recovered();
            } catch (Exception exception) {
                log.warn("Alert rule evaluation failed: ruleId={}, error={}", rule.getId(), exception.getMessage());
            }
        }
        return new EvaluationSummary(evaluated, triggered, recovered);
    }

    public EvaluationSummary evaluateRule(Long ruleId) {
        AlertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: " + ruleId));
        if (!Boolean.TRUE.equals(rule.getEnabled())) return new EvaluationSummary(0, 0, 0);
        return evaluate(rule);
    }

    private EvaluationSummary evaluate(AlertRule rule) {
        List<Condition> conditions = activeConditions(rule);
        TransitionResult transition = persistenceService.evaluate(rule.getId(), rule.getVersion(), conditions);
        transition.deliveries().forEach(delivery -> {
            AlertEvaluationPersistenceService.DeliveryClaim claim;
            try {
                claim = persistenceService.claimDelivery(rule.getId(), delivery);
            } catch (Exception claimError) {
                log.warn("Alert notification could not be claimed: ruleId={}, recordId={}, error={}",
                        rule.getId(), delivery.recordId(), claimError.getMessage());
                return;
            }
            if (claim == null) return;
            AlertNotifyService.AlertDeliveryStatus status;
            String errorMessage = null;
            try {
                status = notifyService.sendAlertWithStatus(
                        claim.rule(), claim.message(), claim.level());
            } catch (Exception notificationError) {
                log.warn("Alert notification failed: ruleId={}, recordId={}, error={}",
                        rule.getId(), delivery.recordId(), notificationError.getMessage());
                status = AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE;
                errorMessage = notificationError.getMessage();
            }
            try {
                persistenceService.recordDelivery(
                        rule.getId(), delivery.recordId(), delivery.kind(), claim.token(),
                        claim.attemptCount(), status, errorMessage);
            } catch (Exception statusError) {
                log.warn("Alert notification status could not be saved: ruleId={}, recordId={}, error={}",
                        rule.getId(), delivery.recordId(), statusError.getMessage());
            }
        });
        return new EvaluationSummary(1, transition.triggered(), transition.recovered());
    }

    private List<Condition> activeConditions(AlertRule rule) {
        return switch (rule.getRuleType()) {
            case "task_failure" -> failedTasks();
            case "data_delay" -> delayedTasks(AlertRuleExpressionParser.delayThresholdMs(rule.getExpression()));
            case "quality_failure" -> unresolvedQualityAlerts();
            default -> throw new IllegalArgumentException("不支持的告警类型: " + rule.getRuleType());
        };
    }

    private List<Condition> failedTasks() {
        return taskRepository.findByStatus(SyncTask.TaskStatus.failed).stream()
                .map(task -> new Condition("task:" + task.getId(),
                        "同步任务失败：" + task.getTaskName() + "；原因："
                                + defaultText(task.getLastErrorMsg(), "请查看 Flink 日志"), "error"))
                .toList();
    }

    private List<Condition> delayedTasks(long thresholdMs) {
        List<Condition> conditions = new ArrayList<>();
        for (SyncTask task : taskRepository.findByStatus(SyncTask.TaskStatus.running)) {
            long lag = task.getCurrentLagMs() == null ? 0L : task.getCurrentLagMs();
            if (lag > thresholdMs) {
                conditions.add(new Condition("task:" + task.getId(),
                        "数据延迟超限：" + task.getTaskName() + "，当前 " + lag
                                + "ms，阈值 " + thresholdMs + "ms", "warn"));
            }
        }
        return conditions;
    }

    private List<Condition> unresolvedQualityAlerts() {
        return qualityAlertRepository.findByResolvedFalseOrderByTriggeredAtDesc().stream()
                .map(this::qualityCondition)
                .toList();
    }

    private Condition qualityCondition(QualityAlert alert) {
        String table = defaultText(alert.getTargetTable(), "未知表");
        return new Condition("quality:" + alert.getId(),
                "数据质量异常：" + table + "；" + defaultText(alert.getMessage(), "质量规则未通过"),
                defaultText(alert.getLevel(), "warn"));
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record EvaluationSummary(int evaluated, int triggered, int recovered) {
    }
}
