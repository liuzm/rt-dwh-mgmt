package com.rtdwh.service;

import com.rtdwh.entity.AlertRecord;
import com.rtdwh.entity.AlertRule;
import com.rtdwh.repository.AlertRecordRepository;
import com.rtdwh.repository.AlertRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceTest {
    private final AlertRuleRepository ruleRepository = mock(AlertRuleRepository.class);
    private final AlertRecordRepository recordRepository = mock(AlertRecordRepository.class);
    private final AlertService service = new AlertService(ruleRepository, recordRepository);

    @Test
    void disablingRuleSuppressesOpenRecordsAndUnclaimedDelivery() {
        AlertRule rule = rule(1L, "quality_failure", true);
        AlertRecord open = openRecord(10L, 1L, "sending");
        open.setDeliveryKind("trigger");
        open.setDeliveryClaimToken("candidate");
        when(ruleRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(1L)).thenReturn(List.of(open));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AlertRule result = service.toggleRule(1L);

        assertFalse(result.getEnabled());
        assertTrue(open.getResolved());
        assertEquals("suppressed", open.getResolutionReason());
        assertEquals("skipped", open.getNotificationStatus());
        assertEquals("skipped", open.getRecoveryNotificationStatus());
        assertNull(open.getDeliveryClaimToken());
        verify(recordRepository).saveAll(List.of(open));
    }

    @Test
    void semanticRuleUpdateUsesTheRuleLockAndClosesOldIncident() {
        AlertRule existing = rule(2L, "task_failure", true);
        existing.setNotifyChannel("dingtalk");
        AlertRule input = rule(null, "data_delay", null);
        input.setExpression(" 5s ");
        input.setNotifyChannel("wecom");
        AlertRecord open = openRecord(20L, 2L, "sent");
        when(ruleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(existing));
        when(recordRepository.findByRuleIdAndResolvedFalse(2L)).thenReturn(List.of(open));
        when(ruleRepository.save(any(AlertRule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AlertRule result = service.updateRule(2L, input);

        assertEquals("data_delay", result.getRuleType());
        assertEquals("5s", result.getExpression());
        assertTrue(result.getEnabled());
        assertEquals("suppressed", open.getResolutionReason());
        verify(ruleRepository).findByIdForUpdate(2L);
    }

    @Test
    void manualResolutionIsAcknowledgedWithoutARecoveryNotification() {
        AlertRecord record = openRecord(30L, 3L, "sent");
        when(recordRepository.findById(30L)).thenReturn(Optional.of(record));
        when(recordRepository.save(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AlertRecord result = service.resolveRecord(30L);

        assertTrue(result.getResolved());
        assertEquals("acknowledged", result.getResolutionReason());
        assertEquals("skipped", result.getRecoveryNotificationStatus());
        verify(ruleRepository).findByIdForUpdate(3L);
    }

    @Test
    void deletingRuleCancelsAlreadyResolvedRecoveryDeliveries() {
        AlertRule rule = rule(4L, "quality_failure", true);
        AlertRecord recovery = openRecord(40L, 4L, "sent");
        recovery.setResolved(true);
        recovery.setResolutionReason("recovered");
        recovery.setRecoveryNotificationStatus("sending");
        recovery.setDeliveryKind("recovery");
        recovery.setDeliveryClaimToken("claim-40");
        when(ruleRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(rule));
        when(recordRepository.findByRuleIdAndResolvedFalse(4L)).thenReturn(List.of());
        when(recordRepository.findByRuleIdAndResolvedTrueAndRecoveryNotificationStatusIn(
                4L, List.of("pending", "sending"))).thenReturn(List.of(recovery));

        service.deleteRule(4L);

        assertEquals("skipped", recovery.getRecoveryNotificationStatus());
        assertEquals("suppressed", recovery.getResolutionReason());
        assertNull(recovery.getDeliveryClaimToken());
        verify(ruleRepository).delete(rule);
    }

    private AlertRule rule(Long id, String type, Boolean enabled) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setRuleName("test rule");
        rule.setRuleType(type);
        rule.setEnabled(enabled);
        rule.setVersion(0L);
        return rule;
    }

    private AlertRecord openRecord(Long id, Long ruleId, String notificationStatus) {
        AlertRecord record = new AlertRecord();
        record.setId(id);
        record.setRuleId(ruleId);
        record.setRuleType("quality_failure");
        record.setResolved(false);
        record.setNotificationStatus(notificationStatus);
        return record;
    }
}
