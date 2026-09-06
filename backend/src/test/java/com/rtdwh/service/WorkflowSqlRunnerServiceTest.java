package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.TaskRunInstance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WorkflowSqlRunnerServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void missingJobAndAmbiguousSubmissionNeverResubmit() {
        WorkflowService workflow = mock(WorkflowService.class);
        FlinkClusterService flink = mock(FlinkClusterService.class);
        WorkflowSqlRunnerService runner = new WorkflowSqlRunnerService(workflow, flink, objectMapper);
        TaskRunInstance run = TaskRunInstance.builder().id(1L).activeAttemptId(2L).executorId("internal-flink-sql")
                .externalJobId("job-1").build();
        when(workflow.runningByExecutor("internal-flink-sql", 200)).thenReturn(java.util.List.of(run));
        when(flink.getJobStatus("job-1")).thenReturn(java.util.Map.of("status", "NOT_FOUND"));
        runner.runCycle();
        verify(workflow).submissionUnknown(1L, 2L, "internal-flink-sql");
        verify(workflow, never()).failOrRetry(any(), any(), any(), any());
        verify(flink, never()).submitViaSqlGateway(any());
    }

    @Test
    void submissionExceptionAfterIntentIsUnknownAndDoesNotCompleteAsFailed() {
        WorkflowService workflow = mock(WorkflowService.class);
        FlinkClusterService flink = mock(FlinkClusterService.class);
        WorkflowSqlRunnerService runner = new WorkflowSqlRunnerService(workflow, flink, objectMapper);
        TaskRunInstance run = TaskRunInstance.builder().id(1L).activeAttemptId(2L).businessDate(LocalDate.of(2026, 9, 5)).build();
        when(workflow.claim("internal-flink-sql")).thenReturn(java.util.Optional.of(run), java.util.Optional.empty());
        when(workflow.taskForInstance(run)).thenReturn(com.rtdwh.entity.SyncTask.builder().id(1L)
                .executionMode(com.rtdwh.entity.SyncTask.ExecutionMode.scheduled).flinkSql("INSERT INTO t SELECT 1").build());
        when(flink.submitViaSqlGateway(any())).thenThrow(new IllegalStateException("response lost"));
        configureCatalog(runner);
        runner.runCycle();
        var order = inOrder(workflow, flink);
        order.verify(workflow).beginSubmission(1L, 2L, "internal-flink-sql");
        order.verify(flink).submitViaSqlGateway(any());
        order.verify(workflow).submissionUnknown(1L, 2L, "internal-flink-sql");
        verify(workflow, never()).complete(any(), anyBoolean(), any(), any(), any());
    }

    private void configureCatalog(WorkflowSqlRunnerService runner) {
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"jdbcUri","jdbc:mysql://mysql/meta");
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"jdbcUser","user");
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"jdbcPassword","runtime'credential");
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"warehouse","/data/paimon");
    }
    @Test void platformSessionResolvesCredentialsAndPreservesUserSql() {
        var runner = new WorkflowSqlRunnerService(null,null,objectMapper); configureCatalog(runner);
        String sql = "INSERT INTO ads.result SELECT * FROM ods.events";
        String resolved = runner.withPlatformCatalog(sql);
        org.junit.jupiter.api.Assertions.assertTrue(resolved.contains("'jdbc.password'='runtime''credential'"));
        org.junit.jupiter.api.Assertions.assertTrue(resolved.endsWith(sql));
        org.junit.jupiter.api.Assertions.assertTrue(resolved.contains("USE CATALOG `rtdwh_paimon`; USE ods;"));
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"metastore","hive");
        assertThrows(IllegalArgumentException.class, () -> runner.withPlatformCatalog(sql));
    }

    @Test
    void rendersBusinessDateAndScalarParameters() {
        TaskRunInstance instance = TaskRunInstance.builder()
                .businessDate(LocalDate.of(2026, 8, 22))
                .parametersJson("{\"region\":\"east\",\"limit\":100}")
                .build();

        String sql = WorkflowSqlRunnerService.renderSql(
                "INSERT INTO sink SELECT * FROM source "
                        + "WHERE dt='${bizdate}' AND region='${region}' LIMIT ${limit}",
                instance, objectMapper);

        assertEquals("INSERT INTO sink SELECT * FROM source "
                + "WHERE dt='2026-08-22' AND region='east' LIMIT 100", sql);
    }

    @Test
    void rejectsUnresolvedParameters() {
        TaskRunInstance instance = TaskRunInstance.builder()
                .businessDate(LocalDate.of(2026, 8, 22))
                .parametersJson("{}")
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> WorkflowSqlRunnerService.renderSql(
                        "INSERT INTO sink SELECT '${missing}'", instance, objectMapper));

        assertEquals("Flink SQL 存在未赋值参数: missing", exception.getMessage());
    }
}
