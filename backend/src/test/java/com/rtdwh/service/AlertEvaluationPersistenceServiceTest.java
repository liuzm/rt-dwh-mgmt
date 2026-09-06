package com.rtdwh.service;

import com.rtdwh.entity.AlertRecord;
import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.repository.AlertRecordRepository;
import com.rtdwh.repository.AlertRuleRepository;
import com.rtdwh.repository.QualityAlertRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AlertEvaluationPersistenceServiceTest {
    private final AlertRuleRepository ruleRepository = mock(AlertRuleRepository.class);
    private final AlertRecordRepository recordRepository = mock(AlertRecordRepository.class);
    private final QualityAlertRepository qualityAlertRepository = mock(QualityAlertRepository.class);
    private final AlertEvaluationPersistenceService service = new AlertEvaluationPersistenceService(
            ruleRepository, recordRepository, qualityAlertRepository);

    @Test
    void createsPendingRecordAndDefersNotification() {
        AlertRule rule = rule(1L, "data_delay");
        var condition = new AlertEvaluationPersistenceService.Condition("task:8", "lagging", "warn");
        when(ruleRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(1L)).thenReturn(List.of());
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                1L, List.of("pending", "sending")))
                .thenReturn(List.of());
        when(recordRepository.save(any(AlertRecord.class))).thenAnswer(invocation -> {
            AlertRecord record = invocation.getArgument(0);
            if (record.getId() == null) record.setId(10L);
            return record;
        });

        var result = service.evaluate(1L, rule.getVersion(), List.of(condition));

        assertEquals(1, result.triggered());
        assertEquals(1, result.deliveries().size());
        assertEquals("pending", captureSavedRecord().getNotificationStatus());
    }

    @Test
    void synchronizesOpenRecordAndNotifiesOnSeverityEscalation() {
        AlertRule rule = rule(2L, "quality_failure");
        AlertRecord open = new AlertRecord();
        open.setId(20L);
        open.setRuleId(2L);
        open.setRuleType("quality_failure");
        open.setDedupKey("quality:9");
        open.setMessage("old message");
        open.setLevel("info");
        open.setResolved(false);
        open.setNotificationStatus("sent");
        var condition = new AlertEvaluationPersistenceService.Condition("quality:9", "new value", "error");
        when(ruleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(2L)).thenReturn(List.of(open));
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                2L, List.of("pending", "sending")))
                .thenReturn(List.of());

        var result = service.evaluate(2L, rule.getVersion(), List.of(condition));

        assertEquals(1, result.triggered());
        assertEquals("new value", open.getMessage());
        assertEquals("error", open.getLevel());
        assertEquals("pending", open.getNotificationStatus());
        assertEquals(AlertEvaluationPersistenceService.DeliveryKind.TRIGGER,
                result.deliveries().get(0).kind());
    }

    @Test
    void acknowledgedQualityAlertDoesNotEmitRecoveryNotification() {
        AlertRule rule = rule(3L, "quality_failure");
        AlertRecord open = new AlertRecord();
        open.setId(30L);
        open.setRuleId(3L);
        open.setRuleType("quality_failure");
        open.setDedupKey("quality:99");
        open.setMessage("quality failed");
        open.setResolved(false);
        QualityAlert acknowledged = QualityAlert.builder().id(99L).resolved(true)
                .resolutionReason("acknowledged").build();
        when(ruleRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(3L)).thenReturn(List.of(open));
        when(qualityAlertRepository.findById(99L)).thenReturn(Optional.of(acknowledged));
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                3L, List.of("pending", "sending")))
                .thenReturn(List.of());

        var result = service.evaluate(3L, rule.getVersion(), List.of());

        assertEquals(1, result.recovered());
        assertTrue(result.deliveries().isEmpty());
        assertEquals("skipped", open.getRecoveryNotificationStatus());
    }

    @Test
    void persistsDeliveryOutcomeInTheMatchingLifecycleField() {
        AlertRecord record = new AlertRecord();
        record.setId(40L);
        record.setRuleId(4L);
        record.setDeliveryKind("recovery");
        record.setDeliveryClaimToken("claim-40");
        record.setRecoveryNotificationStatus("sending");
        when(recordRepository.findById(40L)).thenReturn(Optional.of(record));
        when(recordRepository.finishRecoveryDelivery(
                eq(40L), eq(4L), eq("claim-40"), eq("skipped"), isNull(), isNull())).thenReturn(1);

        assertTrue(service.recordDelivery(4L, 40L, AlertEvaluationPersistenceService.DeliveryKind.RECOVERY,
                "claim-40", 1, AlertNotifyService.AlertDeliveryStatus.SKIPPED, null));

        verify(recordRepository).finishRecoveryDelivery(
                40L, 4L, "claim-40", "skipped", null, null);
    }

    @Test
    void atomicClaimAllowsOnlyOneConcurrentSender() {
        AlertRule rule = rule(5L, "data_delay");
        AlertRecord record = new AlertRecord();
        record.setId(50L);
        record.setRuleId(5L);
        record.setMessage("lagging");
        record.setLevel("warn");
        record.setDeliveryAttemptCount(1);
        when(ruleRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(rule));
        when(recordRepository.claimTriggerDelivery(any(), any(), any(), any(), any()))
                .thenReturn(1, 0);
        when(recordRepository.findById(50L)).thenReturn(Optional.of(record));
        var delivery = new AlertEvaluationPersistenceService.Delivery(
                50L, AlertEvaluationPersistenceService.DeliveryKind.TRIGGER);

        var first = service.claimDelivery(5L, delivery);
        var second = service.claimDelivery(5L, delivery);

        assertNotNull(first);
        assertEquals("lagging", first.message());
        assertNull(second);
    }

    @Test
    void staleWorkerTokenCannotOverwriteANewerDeliveryResult() {
        AlertRecord record = new AlertRecord();
        record.setId(60L);
        record.setRuleId(6L);
        record.setDeliveryKind("trigger");
        record.setDeliveryClaimToken("new-token");
        record.setNotificationStatus("sending");
        when(recordRepository.findById(60L)).thenReturn(Optional.of(record));

        boolean updated = service.recordDelivery(6L, 60L,
                AlertEvaluationPersistenceService.DeliveryKind.TRIGGER, "old-token", 1,
                AlertNotifyService.AlertDeliveryStatus.SENT, null);

        assertFalse(updated);
        verify(recordRepository, never()).save(record);
    }

    @Test
    void retryableFailureReturnsTheClaimToPendingWithBackoff() {
        AlertRecord record = new AlertRecord();
        record.setId(70L);
        record.setRuleId(7L);
        record.setResolved(false);
        record.setDeliveryKind("trigger");
        record.setDeliveryClaimToken("claim-70");
        record.setNotificationStatus("sending");
        when(recordRepository.findById(70L)).thenReturn(Optional.of(record));
        when(recordRepository.finishTriggerDelivery(
                eq(70L), eq(7L), eq("claim-70"), eq("pending"), isNull(), any(), eq("timeout")))
                .thenReturn(1);

        assertTrue(service.recordDelivery(7L, 70L,
                AlertEvaluationPersistenceService.DeliveryKind.TRIGGER, "claim-70", 2,
                AlertNotifyService.AlertDeliveryStatus.RETRYABLE_FAILURE, "timeout"));

        verify(recordRepository).finishTriggerDelivery(
                eq(70L), eq(7L), eq("claim-70"), eq("pending"), isNull(),
                any(), eq("timeout"));
    }

    @Test
    void staleRuleSnapshotCannotApplyOldConditions() {
        AlertRule current = rule(8L, "data_delay");
        current.setVersion(2L);
        when(ruleRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(current));

        var result = service.evaluate(8L, 1L, List.of(
                new AlertEvaluationPersistenceService.Condition("task:1", "late", "warn")));

        assertTrue(result.deliveries().isEmpty());
        verify(recordRepository, never()).findByRuleIdAndResolvedFalse(8L);
    }

    @Test
    void realQualityRecoveryQueuesARecoveryDelivery() {
        AlertRule rule = rule(9L, "quality_failure");
        AlertRecord open = new AlertRecord();
        open.setId(90L);
        open.setRuleId(9L);
        open.setRuleType("quality_failure");
        open.setDedupKey("quality:91");
        open.setMessage("quality failed");
        open.setResolved(false);
        open.setNotificationStatus("sent");
        QualityAlert recovered = QualityAlert.builder().id(91L).resolved(true)
                .resolutionReason("recovered").build();
        when(ruleRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(9L)).thenReturn(List.of(open));
        when(qualityAlertRepository.findById(91L)).thenReturn(Optional.of(recovered));
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                9L, List.of("pending", "sending")))
                .thenReturn(List.of());

        var result = service.evaluate(9L, rule.getVersion(), List.of());

        assertEquals(1, result.recovered());
        assertEquals(AlertEvaluationPersistenceService.DeliveryKind.RECOVERY,
                result.deliveries().get(0).kind());
        assertEquals("recovered", open.getResolutionReason());
    }

    @Test
    void expiredRecoveryClaimIsDiscoveredAndCanBeReclaimed() {
        AlertRule rule = rule(10L, "data_delay");
        AlertRecord stuck = new AlertRecord();
        stuck.setId(100L);
        stuck.setRuleId(10L);
        stuck.setResolved(true);
        stuck.setRecoveryNotificationStatus("sending");
        stuck.setDeliveryKind("recovery");
        stuck.setDeliveryClaimToken("dead-worker");
        stuck.setDeliveryClaimedAt(LocalDateTime.now().minusMinutes(6));
        when(ruleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(10L)).thenReturn(List.of());
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                10L, List.of("pending", "sending"))).thenReturn(List.of(stuck));

        var result = service.evaluate(10L, rule.getVersion(), List.of());

        assertEquals(1, result.deliveries().size());
        assertEquals(AlertEvaluationPersistenceService.DeliveryKind.RECOVERY,
                result.deliveries().get(0).kind());
    }

    @Test
    void oldRecoveryIsCancelledWhenTheSameConditionIsActiveAgain() {
        AlertRule rule = rule(11L, "data_delay");
        AlertRecord oldRecovery = new AlertRecord();
        oldRecovery.setId(110L);
        oldRecovery.setRuleId(11L);
        oldRecovery.setRuleType("data_delay");
        oldRecovery.setDedupKey("task:11");
        oldRecovery.setResolved(true);
        oldRecovery.setNotificationStatus("sent");
        oldRecovery.setRecoveryNotificationStatus("pending");
        when(ruleRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(11L)).thenReturn(List.of());
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                11L, List.of("pending", "sending"))).thenReturn(List.of(oldRecovery));
        when(recordRepository.save(any(AlertRecord.class))).thenAnswer(invocation -> {
            AlertRecord record = invocation.getArgument(0);
            if (record.getId() == null) record.setId(111L);
            return record;
        });

        var result = service.evaluate(11L, rule.getVersion(), List.of(
                new AlertEvaluationPersistenceService.Condition("task:11", "late again", "warn")));

        assertEquals(1, result.deliveries().size());
        assertEquals(AlertEvaluationPersistenceService.DeliveryKind.TRIGGER,
                result.deliveries().get(0).kind());
        assertEquals("skipped", oldRecovery.getRecoveryNotificationStatus());
    }

    @Test
    void resolvedTriggerThatWasSkippedAlsoCancelsRecoveryDelivery() {
        AlertRecord record = new AlertRecord();
        record.setId(120L);
        record.setRuleId(12L);
        record.setResolved(true);
        record.setDeliveryKind("trigger");
        record.setDeliveryClaimToken("claim-120");
        record.setNotificationStatus("sending");
        record.setRecoveryNotificationStatus("pending");
        when(recordRepository.findById(120L)).thenReturn(Optional.of(record));
        when(recordRepository.finishTriggerDelivery(
                eq(120L), eq(12L), eq("claim-120"), eq("skipped"), eq("skipped"), isNull(), isNull()))
                .thenReturn(1);

        assertTrue(service.recordDelivery(12L, 120L,
                AlertEvaluationPersistenceService.DeliveryKind.TRIGGER, "claim-120", 1,
                AlertNotifyService.AlertDeliveryStatus.SKIPPED, null));

        verify(recordRepository).finishTriggerDelivery(
                120L, 12L, "claim-120", "skipped", "skipped", null, null);
    }

    private AlertRule rule(Long id, String type) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setRuleName("rule");
        rule.setRuleType(type);
        rule.setEnabled(true);
        rule.setVersion(0L);
        return rule;
    }

    private AlertRecord captureSavedRecord() {
        var captor = org.mockito.ArgumentCaptor.forClass(AlertRecord.class);
        verify(recordRepository).save(captor.capture());
        return captor.getValue();
    }
}
