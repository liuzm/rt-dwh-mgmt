package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskRunInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSqlRunnerService {
    private final WorkflowService workflowService;
    private final FlinkClusterService flinkClusterService;
    private final ObjectMapper objectMapper;

    @Value("${workflow.runner.executor-id:internal-flink-sql}")
    private String executorId = "internal-flink-sql";

    @Value("${workflow.runner.max-concurrent:2}")
    private int maxConcurrent = 2;

    @Value("${doris.catalog:rtdwh_paimon}") private String platformCatalog = "rtdwh_paimon";
    @Value("${paimon.catalog-key:rtdwh}") private String catalogKey = "rtdwh";
    @Value("${paimon.metastore:jdbc}") private String metastore = "jdbc";
    @Value("${paimon.jdbc-uri}") private String jdbcUri;
    @Value("${paimon.jdbc-user}") private String jdbcUser;
    @Value("${paimon.jdbc-password}") private String jdbcPassword;
    @Value("${paimon.warehouse-path}") private String warehouse;

    /**
     * Reconcile existing jobs before reclaiming leases. This avoids resubmitting a
     * Flink job that kept running while the management process was temporarily down.
     */
    public RunCycleSummary runCycle() {
        int completed = 0;
        int retried = 0;
        List<TaskRunInstance> running = workflowService.runningByExecutor(executorId, 200);
        for (TaskRunInstance instance : running) {
            ReconcileResult result = reconcile(instance);
            completed += result.completed();
            retried += result.retried();
        }

        int recovered = workflowService.recoverExpiredInstances();
        int active = workflowService.runningByExecutor(executorId, 200).size();
        int submitted = 0;
        int capacity = Math.max(0, maxConcurrent - active);
        for (int slot = 0; slot < capacity; slot++) {
            var claimed = workflowService.claim(executorId);
            if (claimed.isEmpty()) break;
            if (submit(claimed.get())) submitted++;
        }
        return new RunCycleSummary(submitted, completed, retried, recovered);
    }

    public TaskRunInstance cancel(Long instanceId) {
        TaskRunInstance instance = workflowService.getInstance(instanceId);
        if (instance.getExternalJobId() != null && !instance.getExternalJobId().isBlank()) {
            flinkClusterService.cancelJob(instance.getExternalJobId());
        }
        return workflowService.cancel(instanceId);
    }

    private boolean submit(TaskRunInstance instance) {
        boolean submitting = false;
        try {
            SyncTask definition = workflowService.taskForInstance(instance);
            if (definition.getExecutionMode() != SyncTask.ExecutionMode.scheduled) {
                workflowService.complete(instance.getId(), false, "持续任务不能作为周期运行实例执行", instance.getActiveAttemptId(), executorId);
                return false;
            }
            String sql = new TaskParameterService(objectMapper).render(definition.getFlinkSql(), definition.getParameterSchemaJson(), instance.getParametersJson(), instance.getBusinessDate());
            SyncTask executable = SyncTask.builder()
                    .id(definition.getId())
                    .taskName(definition.getTaskName() + "@" + instance.getBusinessDate())
                    .taskType(definition.getTaskType())
                    .sourceConfigId(definition.getSourceConfigId())
                    .targetConfigId(definition.getTargetConfigId())
                    .flinkSql(withPlatformCatalog(sql))
                    .parallelism(definition.getParallelism())
                    .checkpointIntervalMs(definition.getCheckpointIntervalMs())
                    .build();
            workflowService.beginSubmission(instance.getId(), instance.getActiveAttemptId(), executorId);
            submitting = true;
            Map<String, Object> result = flinkClusterService.submitViaSqlGateway(executable);
            Object jobId = result.get("jobId");
            if (jobId == null || jobId.toString().isBlank()) {
                throw new IllegalStateException("SQL Gateway 未返回 Flink Job ID");
            }
            workflowService.attachExternalJob(instance.getId(), executorId, jobId.toString(), instance.getActiveAttemptId());
            log.info("Workflow instance {} submitted as Flink job {}", instance.getId(), jobId);
            return true;
        } catch (Exception error) {
            if (submitting) workflowService.submissionUnknown(instance.getId(), instance.getActiveAttemptId(), executorId);
            else workflowService.complete(instance.getId(), false, "提交前校验失败，请核对发布版本、参数和当前权限", instance.getActiveAttemptId(), executorId);
            log.warn("Workflow instance {} could not be submitted; phase={}", instance.getId(), submitting ? "engine" : "validation");
            return false;
        }
    }

    private ReconcileResult reconcile(TaskRunInstance instance) {
        if (instance.getExternalJobId() == null || instance.getExternalJobId().isBlank()) {
            if (instance.getActiveAttemptId() != null && workflowService.attempts(instance.getId()).stream()
                    .anyMatch(attempt -> instance.getActiveAttemptId().equals(attempt.getId()) && "claimed".equals(attempt.getStatus()))) {
                // Do not reclaim another still-live submitter's claim before its lease expires.
                return new ReconcileResult(0, 0);
            }
            workflowService.submissionUnknown(instance.getId(), instance.getActiveAttemptId(), executorId);
            return new ReconcileResult(0, 1);
        }
        try {
            Map<String, Object> status = flinkClusterService.getJobStatus(instance.getExternalJobId());
            String flinkState = String.valueOf(status.getOrDefault("flinkState", ""))
                    .toUpperCase(Locale.ROOT);
            String mappedStatus = String.valueOf(status.getOrDefault("status", ""))
                    .toUpperCase(Locale.ROOT);
            if ("FINISHED".equals(flinkState)) {
                workflowService.complete(instance.getId(), true, null, instance.getActiveAttemptId(), executorId);
                return new ReconcileResult(1, 0);
            }
            if ("NOT_FOUND".equals(mappedStatus)) {
                workflowService.submissionUnknown(instance.getId(), instance.getActiveAttemptId(), executorId);
                return new ReconcileResult(0, 0);
            }
            if ("FAILED".equals(flinkState) || "CANCELED".equals(flinkState)
                    || "FAILED".equals(mappedStatus)) {
                workflowService.failOrRetry(instance.getId(),
                        "Flink Job 已终止，状态: " + (flinkState.isBlank() ? mappedStatus : flinkState), instance.getActiveAttemptId(), executorId);
                return new ReconcileResult(0, 1);
            }

            // RUNNING/CREATED/RESTARTING and temporary UNREACHABLE all retain
            // ownership. Retrying on a network partition could create duplicate writes.
            workflowService.heartbeat(instance.getId(), executorId, instance.getActiveAttemptId());
            return new ReconcileResult(0, 0);
        } catch (Exception exception) {
            workflowService.heartbeat(instance.getId(), executorId, instance.getActiveAttemptId());
            log.warn("Unable to reconcile workflow instance {}: {}",
                    instance.getId(), exception.getMessage());
            return new ReconcileResult(0, 0);
        }
    }

    static String renderSql(String sql, TaskRunInstance instance, ObjectMapper objectMapper) {
        return new TaskParameterService(objectMapper).render(sql, null, instance.getParametersJson(), instance.getBusinessDate());
    }

    // Session bootstrap is resolved only after the frozen runtime and current access checks.
    // Credentials live in this transient submission object, never in the task/version snapshot.
    String withPlatformCatalog(String sql) {
        if (!"jdbc".equals(metastore) || !platformCatalog.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("周期执行暂仅支持受控 JDBC Paimon Catalog");
        return "SET 'execution.runtime-mode' = 'batch'; CREATE CATALOG IF NOT EXISTS `" + platformCatalog
                + "` WITH ('type'='paimon','metastore'='jdbc','uri'=" + literal(jdbcUri)
                + ",'jdbc.user'=" + literal(jdbcUser) + ",'jdbc.password'=" + literal(jdbcPassword)
                + ",'catalog-key'=" + literal(catalogKey) + ",'warehouse'=" + literal(warehouse)
                + "); USE CATALOG `" + platformCatalog + "`; USE ods; " + sql;
    }
    private String literal(String value) {
        if (value == null) throw new IllegalArgumentException("平台 Catalog 配置缺失");
        return "'" + value.replace("'", "''") + "'";
    }

    private record ReconcileResult(int completed, int retried) {}

    public record RunCycleSummary(int submitted, int completed, int retried, int recovered) {}
}
