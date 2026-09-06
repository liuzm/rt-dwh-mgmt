package com.rtdwh.service;

import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityCheckPersistenceServiceTest {
    private final QualityRuleRepository ruleRepository = mock(QualityRuleRepository.class);
    private final QualityAlertRepository alertRepository = mock(QualityAlertRepository.class);
    private final QualityCheckRunRepository runRepository = mock(QualityCheckRunRepository.class);
    private final QualityCheckPersistenceService service = new QualityCheckPersistenceService(
            ruleRepository, alertRepository, runRepository);

    @Test
    void olderRunCannotOverwriteNewerRuleState() {
        QualityRule rule = rule(1L);
        when(ruleRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(rule));
        QualityCheckRun run = run(10L, rule, "failed");
        when(runRepository.finalizeRunningRun(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(runRepository.existsByRuleIdAndScopeKeyAndIdGreaterThanAndStatusNot(1L, "full_table", 10L, "running")).thenReturn(true);

        boolean opened = service.completeRun(rule, run, "error", "failed");

        assertFalse(opened);
        verify(alertRepository, never()).findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(1L);
    }

    @Test
    void refreshesOneOpenAlertAndClosesLegacyDuplicates() {
        QualityRule rule = rule(2L);
        QualityAlert current = QualityAlert.builder().id(20L).ruleId(2L).resolved(false).build();
        QualityAlert duplicate = QualityAlert.builder().id(19L).ruleId(2L).resolved(false).build();
        when(ruleRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(rule));
        when(runRepository.finalizeRunningRun(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(alertRepository.findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(2L))
                .thenReturn(List.of(current, duplicate));

        boolean opened = service.completeRun(rule, run(21L, rule, "failed"), "warn", "failed");

        assertFalse(opened);
        assertEquals(0.2, current.getActualValue());
        assertNotNull(current.getTriggeredAt());
        assertTrue(duplicate.getResolved());
        verify(alertRepository).saveAll(List.of(duplicate));
    }

    @Test
    void recoversStaleRunningRecords() {
        LocalDateTime startedAt = LocalDateTime.now().minusHours(1);
        QualityCheckRun stale = QualityCheckRun.builder().status("running").startedAt(startedAt).build();
        stale.setId(40L);
        stale.setRuleId(4L);
        when(runRepository.findByStatusAndStartedAtBefore("running", startedAt.plusMinutes(30)))
                .thenReturn(List.of(stale));
        when(runRepository.recoverRunningRun(any(), any(), any(), any())).thenReturn(1);
        when(ruleRepository.findByIdForUpdate(4L)).thenReturn(Optional.empty());

        assertEquals(1, service.recoverStaleRuns(startedAt.plusMinutes(30)));

        assertEquals("error", stale.getStatus());
        assertNotNull(stale.getFinishedAt());
        assertTrue(stale.getDurationMs() >= 0);
        verify(runRepository).recoverRunningRun(any(), any(), any(), any());
    }

    @Test
    void rejectsASecondFinalizerAfterTheRunWasAlreadyConverged() {
        QualityRule rule = rule(5L);
        when(runRepository.finalizeRunningRun(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.completeRun(rule, run(50L, rule, "passed"), null, null));

        verify(ruleRepository, never()).findByIdForUpdate(5L);
    }

    @Test
    void passingRunAtomicallyRecoversOpenAlerts() {
        QualityRule rule = rule(6L);
        QualityAlert open = QualityAlert.builder().id(60L).ruleId(6L).resolved(false).build();
        when(runRepository.finalizeRunningRun(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(ruleRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(rule));
        when(alertRepository.findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(6L))
                .thenReturn(List.of(open));

        assertFalse(service.completeRun(rule, run(61L, rule, "passed"), null, null));

        assertTrue(open.getResolved());
        assertEquals("recovered", open.getResolutionReason());
        verify(alertRepository).saveAll(List.of(open));
    }

    @Test
    void changedRuleVersionCannotApplyAnOldRunAlertState() {
        QualityRule current = rule(7L);
        current.setVersion(2L);
        QualityRule snapshot = rule(7L);
        snapshot.setVersion(1L);
        when(runRepository.finalizeRunningRun(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(ruleRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(current));

        assertFalse(service.completeRun(snapshot, run(70L, snapshot, "failed"), "error", "failed"));

        verify(alertRepository, never()).findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(7L);
    }

    @Test
    void staleRunRecoveryCreatesAnErrorAlertForTheCurrentRuleVersion() {
        LocalDateTime startedAt = LocalDateTime.now().minusHours(1);
        QualityRule rule = rule(8L);
        QualityCheckRun stale = QualityCheckRun.builder().id(80L).ruleId(8L).ruleVersion(0L)
                .status("running").thresholdValue(0.05).startedAt(startedAt).build();
        when(runRepository.findByStatusAndStartedAtBefore("running", startedAt.plusMinutes(30)))
                .thenReturn(List.of(stale));
        when(runRepository.recoverRunningRun(any(), any(), any(), any())).thenReturn(1);
        when(ruleRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(rule));
        when(alertRepository.findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(8L)).thenReturn(List.of());

        assertEquals(1, service.recoverStaleRuns(startedAt.plusMinutes(30)));

        var captor = org.mockito.ArgumentCaptor.forClass(QualityAlert.class);
        verify(alertRepository).save(captor.capture());
        assertEquals("error", captor.getValue().getLevel());
        assertEquals(0.05, captor.getValue().getThresholdValue());
        assertTrue(captor.getValue().getMessage().contains("自动收敛"));
    }

    @Test
    void manualResolutionStillWorksAfterRuleWasDeleted() {
        QualityAlert alert = QualityAlert.builder().id(30L).ruleId(3L).resolved(false).build();
        when(ruleRepository.findByIdForUpdate(3L)).thenReturn(Optional.empty());
        when(alertRepository.findById(30L)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(QualityAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        QualityAlert resolved = service.resolveAlertManually(30L, 3L);

        assertTrue(resolved.getResolved());
        assertNotNull(resolved.getResolvedAt());
        assertEquals("acknowledged", resolved.getResolutionReason());
    }

    @Test
    void passingWindowOnlyRecoversAlertsFromTheSameWindow() {
        QualityRule rule = rule(9L);
        QualityAlert yesterday = QualityAlert.builder().id(90L).ruleId(9L).scopeKey("yesterday").resolved(false).build();
        QualityAlert today = QualityAlert.builder().id(91L).ruleId(9L).scopeKey("today").resolved(false).build();
        when(runRepository.finalizeRunningRun(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(ruleRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(rule));
        when(alertRepository.findByRuleIdAndResolvedFalseOrderByTriggeredAtDesc(9L)).thenReturn(List.of(today, yesterday));
        QualityCheckRun run = run(92L, rule, "passed"); run.setScopeKey("today");
        service.completeRun(rule, run, null, null);
        assertTrue(today.getResolved()); assertFalse(yesterday.getResolved());
        verify(runRepository).existsByRuleIdAndScopeKeyAndIdGreaterThanAndStatusNot(9L, "today", 92L, "running");
    }

    private QualityRule rule(Long id) {
        return QualityRule.builder().id(id).ruleType("null_rate").targetTable("orders")
                .targetColumn("id").threshold(0.05).enabled(true).version(0L).build();
    }

    private QualityCheckRun run(Long id, QualityRule rule, String status) {
        return QualityCheckRun.builder().id(id).ruleId(rule.getId()).ruleVersion(rule.getVersion())
                .status(status).checkSql("SELECT 1").actualValue("failed".equals(status) ? 0.2 : 0.01)
                .thresholdValue(rule.getThreshold()).durationMs(1L).finishedAt(LocalDateTime.now()).build();
    }
}
