package com.rtdwh.service;

import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rtdwh.dto.QualityWindow;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityCheckServiceTest {
    private final QualityRuleRepository ruleRepository = mock(QualityRuleRepository.class);
    private final QualityCheckRunRepository runRepository = mock(QualityCheckRunRepository.class);
    private final QualityCheckPersistenceService persistenceService = mock(QualityCheckPersistenceService.class);
    private final DorisConnectionService doris = mock(DorisConnectionService.class);
    private final QualityService qualityService = mock(QualityService.class);
    private final QualityCheckService service = new QualityCheckService(
            ruleRepository, runRepository, persistenceService, doris, qualityService);

    @BeforeEach
    void stubRunPersistence() {
        when(persistenceService.startRun(any(QualityCheckRun.class)))
                .thenAnswer(invocation -> {
                    QualityCheckRun run = invocation.getArgument(0);
                    run.setId(100L);
                    return run;
                });
        when(persistenceService.completeRun(any(QualityRule.class), any(QualityCheckRun.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn(false);
    }

    @Test
    void scheduledBusinessDateUsesConfiguredZoneAcrossUtcMidnight() {
        assertEquals(QualityWindow.forDate(LocalDate.of(2026, 9, 4)),
                service.scheduledWindow(java.time.Instant.parse("2026-09-04T20:00:00Z")));
    }

    @Test
    void generatesFullyQualifiedDorisQualitySql() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule rule = QualityRule.builder().ruleType("null_rate").layer("ods")
                .targetTable("ods_users").targetColumn("user_id").threshold(0.05).build();

        String sql = service.generateCheckSql(rule);
        assertTrue(sql.contains("`user_id` IS NULL"));
        assertTrue(sql.contains("COUNT(*) AS checked_rows"));
        assertTrue(sql.endsWith("FROM `rtdwh_paimon`.`ods`.`ods_users`"));
    }

    @Test
    void usesHalfOpenWindowAndCountsUnknownExpressionAsInvalid() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule rule = QualityRule.builder().ruleType("range_check").layer("dwd")
                .targetTable("users").targetColumn("age").threshold(0.01)
                .expression("age >= 0 AND age <= 150").checkScope("business_window").timeColumn("dt").build();
        assertThrows(IllegalArgumentException.class, () -> service.generateCheckSql(rule));
        String sql = service.generateCheckSql(rule, QualityWindow.forDate(LocalDate.of(2026, 9, 4)));
        assertTrue(sql.contains("CASE WHEN (age >= 0 AND age <= 150) THEN 0 ELSE 1 END"));
        assertTrue(sql.contains("`dt` >= '2026-09-04 00:00:00.000000' AND `dt` < '2026-09-05 00:00:00.000000'"));
    }

    @Test
    void rejectsUnsafeRangeExpression() {
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        QualityRule rule = QualityRule.builder().ruleType("range_check").layer("ods")
                .targetTable("orders").targetColumn("amount").threshold(0.01)
                .expression("amount > 0; DROP TABLE orders").build();

        assertThrows(IllegalArgumentException.class, () -> service.generateCheckSql(rule));
    }

    @Test
    void recordsSuccessfulDorisCheck() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(9L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(9L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getDatabase()).thenReturn("ods");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(2)).thenReturn(100L);
        when(resultSet.getDouble(1)).thenReturn(0.01);

        assertEquals(0, service.runCheck(9L));

        verify(statement).execute("SWITCH `rtdwh_paimon`");
        var runCaptor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), runCaptor.capture(), isNull(), isNull());
        assertEquals("passed", runCaptor.getValue().getStatus());
    }

    @Test
    void recordsSqlGenerationFailureAndReturnsErrorSummary() throws Exception {
        QualityRule rule = QualityRule.builder().id(10L).ruleName("缺少字段")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(10L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");

        var summary = service.runCheckWithSummary(10L);

        assertEquals(1, summary.total());
        assertEquals(1, summary.errorCount());
        assertEquals(1, summary.abnormalCount());
        var runCaptor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), runCaptor.capture(), eq("error"), contains("目标字段"));
        QualityCheckRun run = runCaptor.getValue();
        assertEquals("error", run.getStatus());
        assertEquals("null_rate", run.getRuleType());
        assertEquals("rtdwh_paimon.ods.ods_users", run.getTargetTable());
        assertNotNull(run.getErrorMessage());
        assertNotNull(run.getFinishedAt());
        verify(doris, never()).getConnection();
    }

    @Test
    void passingCheckAutomaticallyResolvesOpenAlert() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(11L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(11L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(2)).thenReturn(100L);
        when(resultSet.getDouble(1)).thenReturn(0.01);

        assertEquals(0, service.runCheck(11L));

        verify(persistenceService).completeRun(eq(rule), any(QualityCheckRun.class), isNull(), isNull());
    }

    @Test
    void atomicCompletionFailureReturnsAnInfrastructureError() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(12L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(12L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(2)).thenReturn(100L);
        when(resultSet.getDouble(1)).thenReturn(0.20);
        when(persistenceService.completeRun(any(), any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        var summary = service.runCheckWithSummary(12L);

        var runCaptor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), runCaptor.capture(), eq("error"), contains("质量检查异常"));
        assertEquals("failed", runCaptor.getValue().getStatus());
        assertEquals(0, summary.failed());
        assertEquals(1, summary.errorCount());
    }

    @Test
    void runFinalizationFailureBlocksTheCheckAsInfrastructureError() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(16L).ruleName("用户ID完整性")
                .ruleType("null_rate").layer("ods").targetTable("ods_users")
                .targetColumn("user_id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(16L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");
        when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(2)).thenReturn(100L);
        when(resultSet.getDouble(1)).thenReturn(0.01);
        when(persistenceService.completeRun(any(), any(), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        var summary = service.runCheckWithSummary(16L);

        assertEquals(0, summary.passed());
        assertEquals(1, summary.errorCount());
        assertEquals(1, summary.abnormalCount());
        verify(persistenceService).completeRun(eq(rule), any(QualityCheckRun.class), isNull(), isNull());
    }

    @Test
    void emptyDataFailsUnlessExplicitlyAllowedAndKeepsRowEvidence() throws Exception {
        Connection connection = mock(Connection.class); Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        QualityRule rule = QualityRule.builder().id(30L).ruleName("空表策略").ruleType("null_rate")
                .layer("ods").targetTable("orders").targetColumn("id").threshold(0.05).enabled(true).build();
        when(ruleRepository.findById(30L)).thenReturn(Optional.of(rule));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon"); when(doris.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(any(String.class))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true); when(resultSet.getLong(2)).thenReturn(0L);
        assertEquals(1, service.runCheckWithSummary(30L).failed());
        var captor = org.mockito.ArgumentCaptor.forClass(QualityCheckRun.class);
        verify(persistenceService).completeRun(eq(rule), captor.capture(), eq("error"), contains("没有数据"));
        assertEquals(0L, captor.getValue().getCheckedRows());
        rule.setEmptyPolicy("allow");
        assertEquals(1, service.runCheckWithSummary(30L).passed());
    }

    @Test
    void historyAccessUsesFrozenTargetEvenAfterRuleRetargetOrDeletion() {
        QualityCheckRun allowed = QualityCheckRun.builder().id(1L).ruleId(9L).targetTable("cat.ods.allowed").build();
        QualityCheckRun forbidden = QualityCheckRun.builder().id(2L).ruleId(9L).targetTable("cat.ods.forbidden").build();
        when(runRepository.findAll()).thenReturn(List.of(allowed, forbidden));
        when(qualityService.canAccessSnapshot(7L, "cat.ods.allowed", null)).thenReturn(true);
        assertEquals(List.of(allowed), service.listRuns(9L, 7L));
        verify(ruleRepository, never()).findById(9L);
    }

    @Test
    void layerCheckUsesLayerQueryAndSkipsDisabledRules() {
        QualityRule disabled = QualityRule.builder().id(13L).enabled(false).layer("ods").build();
        when(ruleRepository.findByLayer("ods")).thenReturn(List.of(disabled));

        assertEquals(0, service.runChecksByLayer("ods"));

        verify(ruleRepository).findByLayer("ods");
        verify(persistenceService, never()).startRun(any());
    }

    @Test
    void productionCheckRequiresExactCatalogDatabaseAndTable() {
        QualityRule otherCatalog = QualityRule.builder().id(14L).enabled(true).layer("ads")
                .targetTable("another_catalog.ads.daily_sales").build();
        QualityRule otherDatabase = QualityRule.builder().id(15L).enabled(true).layer("ads")
                .targetTable("rtdwh_paimon.dws.daily_sales").build();
        when(ruleRepository.findByEnabled(true)).thenReturn(List.of(otherCatalog, otherDatabase));
        when(doris.getCatalog()).thenReturn("rtdwh_paimon");

        assertEquals(0, service.runChecksForTable("rtdwh_paimon", "ads", "daily_sales"));

        verify(persistenceService, never()).startRun(any());
    }
}
