package com.rtdwh.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.SyncTask.TaskStatus;
import com.rtdwh.entity.SyncTask.TaskType;
import com.rtdwh.entity.SyncTask.SyncStrategy;
import com.rtdwh.entity.SyncTask.DefinitionStatus;
import com.rtdwh.entity.SyncTask.ExecutionMode;
import com.rtdwh.dto.SyncTaskCreateDTO;
import com.rtdwh.dto.SyncTaskUpdateDTO;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskDependencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskService {

    private final SyncTaskRepository syncTaskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final FlinkClusterService flinkClusterService;
    private final AlertNotifyService alertNotifyService;
    private final CdcSqlGenerator cdcSqlGenerator;
    private final DatasourceService datasourceService;
    private final ObjectMapper objectMapper;
    private final PostgresCdcService postgresCdcService;
    private final QueryAccessScopeService accessScopeService;
    private final ContinuousDeploymentService continuousDeployments;
    private final DeploymentRevisionPersistenceService deploymentPersistence;

    @Value("${doris.catalog:rtdwh_paimon}")
    private String platformCatalog;

    // ========================================================================
    // CRUD Operations
    // ========================================================================

    @Transactional
    public SyncTask createTask(SyncTaskCreateDTO dto, Long creatorId) {
        TaskType taskType;
        SyncStrategy syncStrategy;
        try {
            taskType = TaskType.valueOf(dto.getTaskType());
            syncStrategy = SyncStrategy.valueOf(dto.getSyncStrategy());
        } catch (Exception e) {
            throw new IllegalArgumentException("任务类型或同步策略不合法");
        }
        String scenarioCode = resolveScenarioCode(dto.getScenarioCode(), taskType);
        TaskCapabilityPolicy.requireSupported(taskType, scenarioCode);
        ExecutionMode executionMode = resolveExecutionMode(dto.getExecutionMode(), scenarioCode, taskType);
        if (taskType == TaskType.cdc_sync) {
            if (dto.getSourceConfigId() == null) throw new IllegalArgumentException("CDC 任务必须选择业务源库");
            DatasourceConfig source = datasourceService.getDatasource(dto.getSourceConfigId());
            if (source.getDbType() != DatasourceConfig.DbType.mysql && source.getDbType() != DatasourceConfig.DbType.postgresql) {
                throw new IllegalArgumentException("CDC 源数据源只支持 MySQL 或 PostgreSQL");
            }
            if (dto.getTableMappings() == null || dto.getTableMappings().isBlank() || !dto.getTableMappings().trim().startsWith("[")) {
                throw new IllegalArgumentException("CDC 任务必须配置表映射");
            }
            try {
                if (!objectMapper.readTree(dto.getTableMappings()).isArray()) throw new IllegalArgumentException("表映射必须是数组");
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("表映射 JSON 格式不正确", e);
            }
        }
        SyncTask task = SyncTask.builder()
                .creatorId(creatorId)
                .taskName(dto.getTaskName())
                .description(dto.getDescription())
                .taskType(taskType)
                .scenarioCode(scenarioCode)
                .executionMode(executionMode)
                .definitionStatus(DefinitionStatus.draft)
                .sourceConfigId(dto.getSourceConfigId())
                // Paimon is a platform runtime configured in Settings. It is no
                // longer modelled as a selectable per-task datasource.
                .targetConfigId(null)
                .flinkSql(dto.getFlinkSql())
                .syncStrategy(syncStrategy)
                .tableMappings(dto.getTableMappings())
                .parallelism(dto.getParallelism() != null ? dto.getParallelism() : 1)
                .checkpointIntervalMs(dto.getCheckpointIntervalMs() != null ? dto.getCheckpointIntervalMs() : 60000L)
                .status(TaskStatus.draft)
                .checkpointCount(0L)
                .build();

        if (!canAccess(creatorId, task)) {
            throw new IllegalArgumentException("无权创建涉及当前数据表的任务");
        }

        return syncTaskRepository.save(task);
    }

    private String resolveScenarioCode(String scenarioCode, TaskType taskType) {
        if (scenarioCode != null && !scenarioCode.isBlank()) return scenarioCode.trim();
        return switch (taskType) {
            case cdc_sync -> "table_realtime_sync";
            case etl -> "sql_transform";
            case materialized -> "materialized_table";
        };
    }

    private ExecutionMode resolveExecutionMode(String requested, String scenarioCode, TaskType taskType) {
        ExecutionMode mode;
        try {
            mode = requested == null || requested.isBlank()
                    ? ("scheduled_sql_output".equals(scenarioCode) ? ExecutionMode.scheduled : ExecutionMode.continuous)
                    : ExecutionMode.valueOf(requested);
        } catch (Exception exception) {
            throw new IllegalArgumentException("运行方式只能是 continuous 或 scheduled");
        }
        if (mode == ExecutionMode.scheduled && taskType != TaskType.etl) {
            throw new IllegalArgumentException("只有周期 SQL 产出任务支持 scheduled 运行方式");
        }
        if (mode == ExecutionMode.scheduled && !"scheduled_sql_output".equals(scenarioCode)) {
            throw new IllegalArgumentException("scheduled 运行方式必须使用定时数据产出场景");
        }
        if ("scheduled_sql_output".equals(scenarioCode) && mode != ExecutionMode.scheduled) {
            throw new IllegalArgumentException("定时数据产出场景必须使用 scheduled 运行方式");
        }
        return mode;
    }

    public SyncTask getTask(Long id) {
        return syncTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    public List<SyncTask> listTasks(TaskStatus status, TaskType taskType, String keyword) {
        return syncTaskRepository.searchTasks(status, taskType, keyword);
    }

    public List<SyncTask> listTasksForUser(Long userId, TaskStatus status, TaskType taskType, String keyword) {
        List<SyncTask> tasks = syncTaskRepository.searchTasks(status, taskType, keyword);
        if (accessScopeService.isAdmin(userId)) return tasks;
        return tasks.stream().filter(task -> canAccess(userId, task)).toList();
    }

    public SyncTask getTaskForUser(Long id, Long userId) {
        SyncTask task = getTask(id);
        if (!canAccess(userId, task)) throw new IllegalArgumentException("无权访问该任务涉及的数据表");
        return task;
    }

    public void assertTaskAccess(Long id, Long userId) {
        getTaskForUser(id, userId);
    }

    @Transactional
    public com.rtdwh.entity.TaskDefinitionVersion publishContinuous(Long id, Long userId, String summary) {
        return continuousDeployments.publish(getTaskForUpdate(id), userId, summary);
    }

    public List<SyncTask> listRunningTasks() {
        return syncTaskRepository.findByStatus(TaskStatus.running);
    }

    public List<SyncTask> listAllActiveTasks() {
        // Tasks that might need status sync (running, saving_point, submitting)
        return syncTaskRepository.findByStatusIn(
            List.of(TaskStatus.running, TaskStatus.saving_point, TaskStatus.submitting));
    }

    @Transactional
    public SyncTask updateTask(Long id, SyncTaskUpdateDTO dto, Long userId) {
        SyncTask task = getTask(id);
        if (task.getStatus() != TaskStatus.draft) {
            throw new IllegalStateException("只能修改 draft 状态的任务配置");
        }

        if (dto.getTaskName() != null) task.setTaskName(dto.getTaskName());
        if (dto.getDescription() != null) task.setDescription(dto.getDescription());
        if (dto.getFlinkSql() != null) task.setFlinkSql(dto.getFlinkSql());
        if (dto.getTableMappings() != null) task.setTableMappings(dto.getTableMappings());
        if (dto.getParallelism() != null) task.setParallelism(dto.getParallelism());
        if (dto.getCheckpointIntervalMs() != null) task.setCheckpointIntervalMs(dto.getCheckpointIntervalMs());
        task.setDefinitionStatus(DefinitionStatus.draft);

        if (!canAccess(userId, task)) throw new IllegalArgumentException("无权修改为涉及当前数据表的任务");

        return syncTaskRepository.save(task);
    }

    @Transactional
    public void deleteTask(Long id) {
        SyncTask task = getTask(id);
        if (task.getStatus() != TaskStatus.draft && task.getStatus() != TaskStatus.finished) {
            throw new IllegalStateException("只能删除 draft 或 finished 状态的任务，当前状态: " + task.getStatus());
        }
        if (task.getTaskType() == TaskType.cdc_sync) {
            DatasourceConfig source = datasourceService.getDatasource(task.getSourceConfigId());
            if (source.getDbType() == DatasourceConfig.DbType.postgresql) postgresCdcService.cleanup(source, task);
        }
        taskDependencyRepository.deleteByUpstreamTaskId(id);
        taskDependencyRepository.deleteByDownstreamTaskId(id);
        syncTaskRepository.delete(task);
    }

    // ========================================================================
    // Task Lifecycle: Start / Pause / Resume / Stop / Retry
    // ========================================================================

    /**
     * Start a task: draft/failed → submitting → running
     * 1. Transition to submitting
     * 2. Submit to Flink cluster (jar run or SQL Gateway)
     * 3. On success: transition to running with jobId
     * 4. On failure: transition to failed with error message
     */
    @Transactional
    public SyncTask startTask(Long id) { return startTask(id, null); }

    @Transactional
    public SyncTask startTask(Long id, Long requestedBy) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "直接启动");

        // Validate state transition
        if (task.getStatus() != TaskStatus.draft && task.getStatus() != TaskStatus.failed) {
            throw new IllegalStateException("无法启动状态为 " + task.getStatus() + " 的任务");
        }

        Long actor = requestedBy == null ? task.getCreatorId() : requestedBy;
        var prepared = continuousDeployments.prepare(task, actor, false);
        SyncTask executable = prepared.executable();
        if (executable.getTaskType() == TaskType.cdc_sync) {
            DatasourceConfig source = datasourceService.getDatasource(executable.getSourceConfigId());
            if (source.getDbType() == DatasourceConfig.DbType.postgresql) postgresCdcService.assertReady(source, executable);
        }
        var revision = deploymentPersistence.begin(executable, prepared.version(), actor, "start", null);
        task.setActiveDeploymentId(revision.getId());

        // Transition to submitting (intermediate state)
        task.setStatus(TaskStatus.submitting);
        task.setLastErrorMsg(null);
        task.setSubmittedAt(LocalDateTime.now());
        syncTaskRepository.save(task);

        try {
            Map<String, Object> submitResult;

            // Choose submission method based on configuration
            if (flinkClusterService.isSqlGatewayEnabled()) {
                // All task types in this platform are represented as Flink SQL.
                // CDC must also use SQL Gateway; the generated CDC SQL is not an
                // executable user JAR and cannot be submitted through /jars/{id}/run.
                submitResult = flinkClusterService.submitViaSqlGateway(executable);
            } else {
                // Compatibility fallback for deployments that provide their own
                // executable job runner JAR.
                submitResult = flinkClusterService.submitJob(executable);
            }

            String jobId = (String) submitResult.get("jobId");
            String jarId = (String) submitResult.get("jarId");
            if (jobId == null || jobId.isBlank()) throw new IllegalStateException("执行引擎未返回作业标识");
            deploymentPersistence.submitted(revision.getId(), jobId);

            // Transition to running
            task.setStatus(TaskStatus.running);
            task.setFlinkJobId(jobId);
            if (jarId != null) task.setFlinkJarId(jarId);
            task.setSubmittedAt(LocalDateTime.now());
            task.setCheckpointCount(0L);
            task.setSavepointTriggerId(null);
            task.setCheckpointInfo(null);

            log.info("Task [{}] started successfully: jobId={}, jarId={}", task.getTaskName(), jobId, jarId);
            return syncTaskRepository.save(task);

        } catch (Exception e) {
            deploymentPersistence.uncertain(revision.getId());
            // Transition to failed
            task.setStatus(TaskStatus.failed);
            task.setLastErrorMsg("部署结果未确认，请查看部署记录并核对 Flink 作业");
            log.error("Task [{}] start failed: {}", task.getTaskName(), e.getMessage());
            return syncTaskRepository.save(task);
        }
    }

    /**
     * Resume a paused task: paused → submitting → running (from savepoint)
     */
    @Transactional
    public SyncTask resumeTask(Long id) { return resumeTask(id, null); }

    @Transactional
    public SyncTask resumeTask(Long id, Long requestedBy) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "恢复");

        if (task.getStatus() != TaskStatus.paused) {
            throw new IllegalStateException("无法恢复状态为 " + task.getStatus() + " 的任务");
        }

        String savepointPath = extractSavepointPath(task.getCheckpointInfo());
        if (savepointPath == null) {
            throw new IllegalStateException("未找到 savepoint 路径，无法恢复。请从 draft 状态重新启动。");
        }

        Long actor = requestedBy == null ? task.getCreatorId() : requestedBy;
        var prepared = continuousDeployments.prepare(task, actor, true);
        SyncTask executable = prepared.executable();
        var revision = deploymentPersistence.begin(executable, prepared.version(), actor, "resume", savepointPath);
        task.setActiveDeploymentId(revision.getId());
        // Transition to submitting
        task.setStatus(TaskStatus.submitting);
        task.setSubmittedAt(LocalDateTime.now());
        syncTaskRepository.save(task);

        try {
            Map<String, Object> submitResult;

            if (flinkClusterService.isSqlGatewayEnabled()) {
                submitResult = flinkClusterService.submitViaSqlGateway(executable, savepointPath);
            } else {
                submitResult = flinkClusterService.submitFromSavepoint(executable, savepointPath);
            }

            String jobId = (String) submitResult.get("jobId");
            if (jobId == null || jobId.isBlank()) throw new IllegalStateException("执行引擎未返回作业标识");
            deploymentPersistence.submitted(revision.getId(), jobId);

            task.setStatus(TaskStatus.running);
            task.setFlinkJobId(jobId);
            task.setSubmittedAt(LocalDateTime.now());
            task.setSavepointTriggerId(null);

            log.info("Task [{}] resumed from savepoint: jobId={}, savepoint={}",
                task.getTaskName(), jobId, savepointPath);
            return syncTaskRepository.save(task);

        } catch (Exception e) {
            deploymentPersistence.uncertain(revision.getId());
            task.setStatus(TaskStatus.failed);
            task.setLastErrorMsg("恢复结果未确认，请查看部署记录并核对 Flink 作业");
            return syncTaskRepository.save(task);
        }
    }

    /**
     * Pause a running task: running → saving_point → paused
     * 1. Trigger stop-with-savepoint on Flink
     * 2. Transition to saving_point with triggerId
     * 3. Need to poll for savepoint completion (done by status monitor)
     */
    @Transactional
    public SyncTask pauseTask(Long id) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "暂停");

        if (task.getStatus() != TaskStatus.running) {
            throw new IllegalStateException("无法暂停状态为 " + task.getStatus() + " 的任务");
        }

        if (task.getFlinkJobId() == null) {
            throw new IllegalStateException("任务没有关联的 Flink Job ID");
        }

        // Trigger async stop-with-savepoint
        Map<String, Object> triggerResult = flinkClusterService.triggerStopWithSavepoint(task.getFlinkJobId());
        String triggerId = (String) triggerResult.get("triggerId");

        // Transition to saving_point (intermediate state)
        task.setStatus(TaskStatus.saving_point);
        task.setSavepointTriggerId(triggerId);

        log.info("Task [{}] pausing: triggerId={}", task.getTaskName(), triggerId);
        return syncTaskRepository.save(task);
    }

    /**
     * Stop a task immediately (no savepoint): running/saving_point/paused → finished
     */
    @Transactional
    public SyncTask stopTask(Long id) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "停止");

        if (task.getStatus() == TaskStatus.draft || task.getStatus() == TaskStatus.finished) {
            throw new IllegalStateException("无法停止状态为 " + task.getStatus() + " 的任务");
        }

        // Cancel Flink job if running
        if (task.getFlinkJobId() != null) {
            flinkClusterService.cancelJob(task.getFlinkJobId());
        }

        task.setStatus(TaskStatus.finished);
        task.setSavepointTriggerId(null);

        log.info("Task [{}] stopped (no savepoint)", task.getTaskName());
        return syncTaskRepository.save(task);
    }

    /**
     * Retry a failed task: failed → submitting → running
     * Same as startTask but specifically for failed state.
     */
    @Transactional
    public SyncTask retryTask(Long id) { return retryTask(id, null); }

    @Transactional
    public SyncTask retryTask(Long id, Long requestedBy) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "重新启动");

        if (task.getStatus() != TaskStatus.failed) {
            throw new IllegalStateException("只能重试 failed 状态的任务");
        }

        // Cancel any leftover Flink job first
        if (task.getFlinkJobId() != null) {
            try {
                flinkClusterService.cancelJob(task.getFlinkJobId());
            } catch (Exception e) {
                log.warn("Failed to cancel old Flink job on retry: {}", e.getMessage());
            }
        }

        // Clear previous job info and restart
        task.setFlinkJobId(null);
        task.setFlinkJarId(null);
        task.setLastErrorMsg(null);
        task.setSavepointTriggerId(null);
        syncTaskRepository.save(task);

        return startTask(id, requestedBy);
    }

    public Map<String, Object> getPostgresCdcStatus(Long id) {
        SyncTask task = getTask(id);
        DatasourceConfig source = datasourceService.getDatasource(task.getSourceConfigId());
        return postgresCdcService.preflight(source, task);
    }

    @Transactional
    public Map<String, Object> cleanupPostgresCdcResources(Long id) {
        SyncTask task = getTask(id);
        if (task.getStatus() == TaskStatus.running || task.getStatus() == TaskStatus.submitting
                || task.getStatus() == TaskStatus.saving_point) {
            throw new IllegalStateException("请先停止 Flink CDC 任务，再清理 PostgreSQL Slot/Publication");
        }
        DatasourceConfig source = datasourceService.getDatasource(task.getSourceConfigId());
        return postgresCdcService.cleanup(source, task);
    }

    // ========================================================================
    // Savepoint Operations
    // ========================================================================

    /**
     * Trigger a manual savepoint (without stopping the job).
     * running → running (triggerId stored, polled by monitor)
     */
    @Transactional
    public SyncTask triggerManualSavepoint(Long id) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "触发 Savepoint");

        if (task.getStatus() != TaskStatus.running) {
            throw new IllegalStateException("只能对 running 状态的任务触发 Savepoint");
        }

        if (task.getFlinkJobId() == null) {
            throw new IllegalStateException("任务没有关联的 Flink Job ID");
        }

        Map<String, Object> triggerResult = flinkClusterService.triggerSavepoint(task.getFlinkJobId());
        String triggerId = (String) triggerResult.get("triggerId");

        task.setSavepointTriggerId(triggerId);
        log.info("Manual savepoint triggered for task [{}]: triggerId={}", task.getTaskName(), triggerId);
        return syncTaskRepository.save(task);
    }

    // ========================================================================
    // Status Monitoring & Auto-sync
    // ========================================================================

    /**
     * Get task status, combining DB status and Flink real-time metrics.
     */
    public Map<String, Object> getTaskStatus(Long id) {
        SyncTask task = getTask(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskStatus", task.getStatus().name());
        result.put("taskId", task.getId());
        result.put("taskName", task.getTaskName());
        if (task.getTaskType() == TaskType.cdc_sync && task.getSourceConfigId() != null) {
            result.put("sourceDbType", datasourceService.getDatasource(task.getSourceConfigId()).getDbType().name());
        }

        if (task.getFlinkJobId() == null) {
            result.put("flinkJobStatus", "NO_JOB");
            result.put("currentLagMs", 0);
            result.put("throughputQps", 0.0);
            result.put("lastErrorMsg", task.getLastErrorMsg());
            return result;
        }

        // Poll Flink for real-time status
        Map<String, Object> flinkStatus = flinkClusterService.getJobStatus(task.getFlinkJobId());

        result.put("flinkJobId", task.getFlinkJobId());
        result.put("flinkJobStatus", flinkStatus.get("flinkState"));
        result.put("currentLagMs", flinkStatus.getOrDefault("lagMs", 0L));
        result.put("throughputQps", flinkStatus.getOrDefault("throughputQps", 0.0));
        result.put("checkpointInfo", flinkStatus.get("checkpointInfo"));
        result.put("lastErrorMsg", task.getLastErrorMsg());
        result.put("checkpointCount", task.getCheckpointCount());
        result.put("submittedAt", task.getSubmittedAt() != null ? task.getSubmittedAt().toString() : null);
        result.put("lastCheckpointTime", task.getLastCheckpointTime() != null ? task.getLastCheckpointTime().toString() : null);

        // If there's an active savepoint trigger, poll its progress
        if (task.getSavepointTriggerId() != null) {
            Map<String, Object> spStatus = flinkClusterService.pollSavepointStatus(
                task.getFlinkJobId(), task.getSavepointTriggerId());
            result.put("savepointProgress", spStatus.get("status"));
            result.put("savepointTriggerId", task.getSavepointTriggerId());
        }

        return result;
    }

    /** Return fresh adaptive-scaling state for the Flink job behind a task. */
    public Map<String, Object> getTaskScaling(Long id) {
        SyncTask task = getTask(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("taskStatus", task.getStatus().name());
        result.put("configuredParallelism", task.getParallelism() == null ? 1 : task.getParallelism());

        if (task.getExecutionMode() == ExecutionMode.scheduled) {
            result.put("supported", false);
            result.put("jobId", null);
            result.put("reason", "周期任务按运行实例管理，不支持任务级动态扩缩容");
            result.put("capacity", flinkClusterService.getClusterCapacity());
            return result;
        }

        if (task.getFlinkJobId() == null || task.getFlinkJobId().isBlank()) {
            result.put("supported", false);
            result.put("jobId", null);
            result.put("reason", "任务尚未关联正在运行的 Flink Job");
            result.put("capacity", flinkClusterService.getClusterCapacity());
            return result;
        }

        result.putAll(flinkClusterService.getJobScalingInfo(task.getFlinkJobId()));
        result.put("configuredParallelism", task.getParallelism() == null ? 1 : task.getParallelism());
        if (submitsMultipleFlinkJobs(task)) {
            result.put("supported", false);
            result.put("reason", "该旧版任务可能对应多个 Flink Job，无法安全统一扩缩；请在 Flink UI 逐个取消关联 Job，再删除并按 Statement Set 单 Job 方式重建任务");
        }
        return result;
    }

    /** Submit a guarded, in-place adaptive parallelism change for a running task. */
    @Transactional
    public Map<String, Object> rescaleTask(
            Long id,
            int targetParallelism,
            String expectedJobId,
            int expectedConfiguredParallelism,
            String reason,
            String requestedBy
    ) {
        SyncTask task = getTaskForUpdate(id);
        requireContinuous(task, "调整并行度");
        if (task.getStatus() != TaskStatus.running) {
            throw new IllegalStateException("仅运行中的任务可以调整并行度");
        }
        if (task.getFlinkJobId() == null || !task.getFlinkJobId().equals(expectedJobId)) {
            throw new IllegalStateException("Flink Job 已发生变化，请刷新页面后重试");
        }
        int configuredParallelism = task.getParallelism() == null ? 1 : task.getParallelism();
        if (configuredParallelism != expectedConfiguredParallelism) {
            throw new IllegalStateException("任务并行度已被其他操作修改，请刷新页面后重试");
        }
        if (submitsMultipleFlinkJobs(task)) {
            throw new IllegalStateException("该旧版任务可能对应多个 Flink Job；请在 Flink UI 逐个取消关联 Job，再删除并按 Statement Set 单 Job 方式重建任务");
        }

        Map<String, Object> result = new LinkedHashMap<>(
                flinkClusterService.rescaleJob(task.getFlinkJobId(), targetParallelism));
        // The accepted target is the desired parallelism for future retry and
        // savepoint resume submissions as well, not just this Job incarnation.
        task.setParallelism(targetParallelism);
        syncTaskRepository.saveAndFlush(task);
        result.put("taskId", task.getId());
        result.put("configuredParallelism", targetParallelism);
        result.put("reason", reason.trim());
        result.put("requestedBy", requestedBy);
        log.info("Flink job rescale accepted: taskId={}, jobId={}, targetParallelism={}, requestedBy={}, reason={}",
                task.getId(), task.getFlinkJobId(), targetParallelism, requestedBy, reason);
        return result;
    }

    /**
     * Sync task status from Flink cluster (called by scheduled monitor).
     * For each running/submitting/saving_point task:
     * 1. Poll Flink job status
     * 2. If Flink says FAILED → update DB status to failed
     * 3. If Flink says CANCELED/FINISHED → update DB to finished
     * 4. Update metrics (lag, throughput, checkpoint count)
     * 5. If saving_point and savepoint completed → update to paused
     */
    @Transactional
    public int syncTaskStatusFromFlink() {
        List<SyncTask> activeTasks = listAllActiveTasks();
        int syncedCount = 0;

        for (SyncTask task : activeTasks) {
            try {
                if (task.getFlinkJobId() == null) {
                    // No Flink job yet, check if stuck in submitting
                    if (task.getStatus() == TaskStatus.submitting) {
                        // Submitting timeout: if > 5 minutes, mark as failed
                        if (task.getSubmittedAt() != null &&
                            task.getSubmittedAt().isBefore(LocalDateTime.now().minusMinutes(5))) {
                            task.setStatus(TaskStatus.failed);
                            task.setLastErrorMsg("提交超时: 5分钟内未获得 Flink Job ID");
                            syncTaskRepository.save(task);
                            syncedCount++;
                        }
                    }
                    continue;
                }

                Map<String, Object> flinkStatus = flinkClusterService.getJobStatus(task.getFlinkJobId());
                String flinkState = (String) flinkStatus.get("flinkState");

                if (flinkState == null || "UNREACHABLE".equals(flinkStatus.get("status"))) {
                    log.debug("Flink status unavailable for task [{}], keeping current state", task.getTaskName());
                    continue;
                }

                syncedCount++;

                // Stop-with-savepoint commonly finishes the Job before the monitor polls it.
                // Retain the operation handle until its own result is known.
                if (task.getStatus() == TaskStatus.saving_point && task.getSavepointTriggerId() != null) {
                    checkSavepointProgress(task, flinkState);
                    continue;
                }
                // Handle Flink state changes
                switch (flinkState.toUpperCase(java.util.Locale.ROOT)) {
                    case "FAILED":
                    case "FAILING":
                        handleFlinkJobFailure(task, flinkStatus);
                        break;

                    case "CANCELED":
                    case "FINISHED":
                        handleFlinkJobCompletion(task, flinkState);
                        break;

                    case "NOT_FOUND":
                        handleMissingFlinkJob(task);
                        break;

                    case "SUSPENDED":
                        handleSuspendedFlinkJob(task);
                        break;

                    case "RUNNING":
                    case "RESTARTING":
                        updateRunningTaskMetrics(task, flinkStatus);
                        break;

                    default:
                        log.debug("Task [{}] Flink state: {}", task.getTaskName(), flinkState);
                }

                if (task.getStatus() == TaskStatus.running && task.getSavepointTriggerId() != null) {
                    checkSavepointProgress(task, flinkState);
                }

            } catch (Exception e) {
                log.warn("Failed to sync task [{}]: {}", task.getTaskName(), e.getMessage());
            }
        }

        log.info("Synced {} active tasks from Flink cluster", syncedCount);
        return syncedCount;
    }

    private void handleFlinkJobFailure(SyncTask task, Map<String, Object> flinkStatus) {
        // If currently saving_point and Flink says FAILED, it might be the savepoint operation failing
        if (task.getStatus() == TaskStatus.saving_point) {
            // Check savepoint progress specifically
            if (task.getSavepointTriggerId() != null) {
                Map<String, Object> spStatus = flinkClusterService.pollSavepointStatus(
                    task.getFlinkJobId(), task.getSavepointTriggerId());
                if ("FAILED".equals(spStatus.get("status"))) {
                    // Savepoint failed, but job might still be running
                    // Revert to running state
                    task.setStatus(TaskStatus.running);
                    task.setSavepointTriggerId(null);
                    task.setLastErrorMsg("Savepoint 失败: " + spStatus.get("failureCause"));
                    syncTaskRepository.save(task);
                    return;
                }
            }
        }

        task.setStatus(TaskStatus.failed);
        task.setLastErrorMsg("Flink Job 失败。请查看日志获取详细信息。");
        task.setSavepointTriggerId(null);
        syncTaskRepository.save(task);

        // Send alert notification for task failure
        try {
            alertNotifyService.sendTaskFailureAlert(task.getTaskName(), task.getLastErrorMsg());
        } catch (Exception e) {
            log.warn("Failed to send task failure alert: {}", e.getMessage());
        }

        log.warn("Task [{}] marked as FAILED (Flink state)", task.getTaskName());
    }

    private void handleFlinkJobCompletion(SyncTask task, String flinkState) {
        // Flink job finished or was canceled externally
        TaskStatus previousStatus = task.getStatus();
        task.setStatus(TaskStatus.finished);
        task.setSavepointTriggerId(null);

        if ("CANCELED".equals(flinkState) && previousStatus != TaskStatus.saving_point) {
            task.setLastErrorMsg("Flink Job 被外部取消");
        }

        syncTaskRepository.save(task);
        log.info("Task [{}] marked as FINISHED (Flink state: {})", task.getTaskName(), flinkState);
    }

    private void handleMissingFlinkJob(SyncTask task) {
        task.setStatus(TaskStatus.finished);
        task.setSavepointTriggerId(null);
        task.setCurrentLagMs(0L);
        task.setThroughputQps(0.0);
        task.setLastErrorMsg("Flink 集群中已不存在该 Job，状态已自动校准为已终止");
        syncTaskRepository.save(task);
        log.info("Task [{}] marked as FINISHED because Flink job [{}] was not found",
                task.getTaskName(), task.getFlinkJobId());
    }

    private void handleSuspendedFlinkJob(SyncTask task) {
        task.setStatus(TaskStatus.paused);
        task.setSavepointTriggerId(null);
        task.setCurrentLagMs(0L);
        task.setThroughputQps(0.0);
        task.setLastErrorMsg("Flink Job 已进入 SUSPENDED 状态，任务已自动校准为暂停");
        syncTaskRepository.save(task);
        log.info("Task [{}] marked as PAUSED (Flink state: SUSPENDED)", task.getTaskName());
    }

    private void updateRunningTaskMetrics(SyncTask task, Map<String, Object> flinkStatus) {
        Long lagMs = ((Number) flinkStatus.getOrDefault("lagMs", 0L)).longValue();
        Double throughputQps = ((Number) flinkStatus.getOrDefault("throughputQps", 0.0)).doubleValue();

        task.setCurrentLagMs(lagMs);
        task.setThroughputQps(throughputQps);

        // Update checkpoint info
        Map<String, Object> checkpointInfo = (Map<String, Object>) flinkStatus.get("checkpointInfo");
        if (checkpointInfo != null) {
            Long count = ((Number) checkpointInfo.getOrDefault("completedCount", 0L)).longValue();
            Long lastTs = ((Number) checkpointInfo.getOrDefault("lastCompletedTimestamp", 0L)).longValue();

            task.setCheckpointCount(count);
            if (lastTs > 0) {
                task.setLastCheckpointTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(lastTs), java.time.ZoneId.systemDefault()));
            }
            try {
                task.setCheckpointInfo(objectMapper.writeValueAsString(checkpointInfo));
            } catch (JsonProcessingException e) {
                log.warn("序列化 checkpointInfo 失败, taskId={}, error={}", task.getId(), e.getMessage());
            }
        }

        syncTaskRepository.save(task);
    }

    private void checkSavepointProgress(SyncTask task, String flinkState) {
        boolean stopping = task.getStatus() == TaskStatus.saving_point;
        Map<String, Object> spStatus = flinkClusterService.pollSavepointStatus(
            task.getFlinkJobId(), task.getSavepointTriggerId());

        String spProgress = (String) spStatus.get("status");

        if ("COMPLETED".equals(spProgress)) {
            String savepointPath = (String) spStatus.get("savepointPath");

            if (savepointPath == null || savepointPath.isBlank()) return;
            // A manual savepoint retains a running Job; only stop-with-savepoint pauses it.
            task.setStatus(stopping ? TaskStatus.paused : TaskStatus.running);
            try {
                task.setCheckpointInfo(objectMapper.writeValueAsString(
                    Map.of("savepointPath", savepointPath)));
            } catch (Exception jsonEx) {
                log.warn("Failed to serialize savepoint path: {}", jsonEx.getMessage());
            }
            task.setSavepointTriggerId(null);
            syncTaskRepository.save(task);

            log.info("Task [{}] paused successfully, savepoint at: {}", task.getTaskName(), savepointPath);
        } else if ("FAILED".equals(spProgress)) {
            // Restore running only when the engine confirms it is still running.
            task.setStatus("RUNNING".equals(flinkState) ? TaskStatus.running : TaskStatus.failed);
            task.setSavepointTriggerId(null);
            task.setLastErrorMsg("Savepoint 失败: " + spStatus.get("failureCause"));
            syncTaskRepository.save(task);

            log.warn("Task [{}] savepoint failed, reverted to running", task.getTaskName());
        }
        // PENDING / IN_PROGRESS: keep in saving_point state, next poll will check again
    }

    // ========================================================================
    // Task Logs
    // ========================================================================

    public Map<String, Object> getTaskLogs(Long id, String type, int lines) {
        SyncTask task = getTask(id);
        if (task.getFlinkJobId() == null) {
            return Map.of("logs", "任务未运行，无日志", "type", type);
        }
        return flinkClusterService.getJobLogs(task.getFlinkJobId(), type, lines);
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private SyncTask getTaskForUpdate(Long id) {
        return syncTaskRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    private void requireContinuous(SyncTask task, String action) {
        if (task.getExecutionMode() == ExecutionMode.scheduled) {
            throw new IllegalStateException("周期任务不能" + action + "；请发布版本后通过调度或补数创建运行实例");
        }
    }

    private boolean canAccess(Long userId, SyncTask task) {
        if (accessScopeService.isAdmin(userId)) return true;
        if (task.getTaskType() != TaskType.cdc_sync) {
            try {
                return task.getFlinkSql() != null && accessScopeService.canAccessSql(
                        userId, new TaskParameterService(objectMapper).forAccessCheck(task.getFlinkSql()), platformCatalog, "ods");
            } catch (IllegalArgumentException invalid) { return false; }
        }
        try {
            var mappings = objectMapper.readTree(task.getTableMappings());
            if (!mappings.isArray() || mappings.isEmpty()) return false;
            for (var mapping : mappings) {
                String database = mapping.path("targetDb").asText("ods");
                String table = mapping.path("targetTable").asText();
                if (table.isBlank() || !accessScopeService.allowed(userId, platformCatalog, database, table)) return false;
            }
            return true;
        } catch (Exception invalidMappings) {
            return false;
        }
    }

    /**
     * Before multi-table CDC switched to Statement Set, each INSERT was
     * submitted independently and produced a separate Job. A single stored
     * Job ID cannot safely represent or rescale that group.
     */
    private boolean submitsMultipleFlinkJobs(SyncTask task) {
        if (task.getFlinkSql() == null || task.getFlinkSql().isBlank()) return false;
        return FlinkClusterService.createsMultipleJobs(
                FlinkClusterService.splitSqlStatements(task.getFlinkSql()));
    }

    private String extractSavepointPath(String checkpointInfo) {
        if (checkpointInfo == null) return null;
        try {
            Map<String, String> info = objectMapper.readValue(checkpointInfo, Map.class);
            return info.get("savepointPath");
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint info: {}", e.getMessage());
            return null;
        }
    }
}
