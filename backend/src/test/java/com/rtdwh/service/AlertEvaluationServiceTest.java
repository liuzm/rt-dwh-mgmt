package com.rtdwh.service;

import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.SyncTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertEvaluationServiceTest {
    private final AlertRuleRepository ruleRepository = mock(AlertRuleRepository.class);
    private final SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
    private final QualityAlertRepository qualityAlertRepository = mock(QualityAlertRepository.class);
    private final AlertNotifyService notifyService = mock(AlertNotifyService.class);
    private final AlertEvaluationPersistenceService persistenceService = mock(AlertEvaluationPersistenceService.class);
    private final AlertEvaluationService service = new AlertEvaluationService(
            ruleRepository, taskRepository, qualityAlertRepository, notifyService, persistenceService);

    @Test
    void parsesDelayUnitsAndRejectsInvalidExpression() {
        assertEquals(5_000L, AlertRuleExpressionParser.delayThresholdMs("5s"));
        assertEquals(120_000L, AlertRuleExpressionParser.delayThresholdMs("2m"));
        assertEquals(6_000L, AlertRuleExpressionParser.delayThresholdMs("lag_ms > 6000"));
        assertThrows(IllegalArgumentException.class,
                () -> AlertRuleExpressionParser.delayThresholdMs("lag > now()"));
    }

    @Test
    void sendsNewAlertOnlyAfterPersistenceTransitionReturns() {
        AlertRule rule = rule(1L, "data_delay", "5s");
        SyncTask task = SyncTask.builder().id(8L).taskName("orders-cdc")
                .status(SyncTask.TaskStatus.running).currentLagMs(7_500L).build();
        var condition = new AlertEvaluationPersistenceService.Condition(
                "task:8", "数据延迟超限：orders-cdc，当前 7500ms，阈值 5000ms", "warn");
        var delivery = new AlertEvaluationPersistenceService.Delivery(
                10L, AlertEvaluationPersistenceService.DeliveryKind.TRIGGER);
        var claim = new AlertEvaluationPersistenceService.DeliveryClaim(
                rule, "claim-10", condition.message(), condition.level(), 1);
        when(ruleRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(taskRepository.findByStatus(SyncTask.TaskStatus.running)).thenReturn(List.of(task));
        when(persistenceService.evaluate(1L, rule.getVersion(), List.of(condition)))
                .thenReturn(new AlertEvaluationPersistenceService.TransitionResult(rule, 1, 0, List.of(delivery)));
        when(persistenceService.claimDelivery(1L, delivery)).thenReturn(claim);
        when(notifyService.sendAlertWithStatus(any(), any(), any()))
                .thenReturn(AlertNotifyService.AlertDeliveryStatus.SENT);

        AlertEvaluationService.EvaluationSummary summary = service.evaluateAll();

        assertEquals(1, summary.triggered());
        verify(notifyService).sendAlertWithStatus(rule, condition.message(), "warn");
        verify(persistenceService).recordDelivery(1L, 10L,
                AlertEvaluationPersistenceService.DeliveryKind.TRIGGER, "claim-10", 1,
                AlertNotifyService.AlertDeliveryStatus.SENT, null);
    }

    @Test
    void doesNotNotifyAgainWhenTransitionHasNoDelivery() {
        AlertRule rule = rule(1L, "data_delay", "5000ms");
        SyncTask task = SyncTask.builder().id(8L).taskName("orders-cdc")
                .status(SyncTask.TaskStatus.running).currentLagMs(7_500L).build();
        var condition = new AlertEvaluationPersistenceService.Condition(
                "task:8", "数据延迟超限：orders-cdc，当前 7500ms，阈值 5000ms", "warn");
        when(ruleRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(taskRepository.findByStatus(SyncTask.TaskStatus.running)).thenReturn(List.of(task));
        when(persistenceService.evaluate(1L, rule.getVersion(), List.of(condition)))
                .thenReturn(new AlertEvaluationPersistenceService.TransitionResult(rule, 0, 0, List.of()));

        AlertEvaluationService.EvaluationSummary summary = service.evaluateAll();

        assertEquals(0, summary.triggered());
        verify(notifyService, never()).sendAlertWithStatus(any(), any(), any());
    }

    @Test
    void recordsRetryableDeliveryFailureAsPending() {
        AlertRule rule = rule(1L, "data_delay", "5s");
        SyncTask task = SyncTask.builder().id(8L).taskName("orders-cdc")
                .status(SyncTask.TaskStatus.running).currentLagMs(7_500L).build();
        var condition = new AlertEvaluationPersistenceService.Condition(
                "task:8", "数据延迟超限：orders-cdc，当前 7500ms，阈值 5000ms", "warn");
        var delivery = new AlertEvaluationPersistenceService.Delivery(
                11L, AlertEvaluationPersistenceService.DeliveryKind.TRIGGER);
        var claim = new AlertEvaluationPersistenceService.DeliveryClaim(
                rule, "claim-11", condition.message(), "warn", 2);
        when(ruleRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(taskRepository.findByStatus(SyncTask.TaskStatus.running)).thenReturn(List.of(task));
        when(persistenceService.evaluate(1L, rule.getVersion(), List.of(condition)))
                .thenReturn(new AlertEvaluationPersistenceService.TransitionResult(rule, 0, 0, List.of(delivery)));
        when(persistenceService.claimDelivery(1L, delivery)).thenReturn(claim);
        when(notifyService.sendAlertWithStatus(any(), any(), any()))
                .thenReturn(AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE);

        service.evaluateAll();

        verify(persistenceService).recordDelivery(1L, 11L,
                AlertEvaluationPersistenceService.DeliveryKind.TRIGGER, "claim-11", 2,
                AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE, null);
    }

    private AlertRule rule(Long id, String type, String expression) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setRuleName("test-rule");
        rule.setRuleType(type);
        rule.setExpression(expression);
        rule.setNotifyChannel("dingtalk");
        rule.setEnabled(true);
        rule.setVersion(0L);
        return rule;
    }
}
