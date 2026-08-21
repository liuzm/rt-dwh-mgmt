package com.rtdwh.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.SyncTask.TaskStatus;
import com.rtdwh.entity.SyncTask.TaskType;
import com.rtdwh.entity.SyncTask.SyncStrategy;
import com.rtdwh.dto.SyncTaskCreateDTO;
import com.rtdwh.dto.SyncTaskUpdateDTO;
import com.rtdwh.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskService {

    private final SyncTaskRepository syncTaskRepository;
    private final FlinkClusterService flinkClusterService;
    private final AlertNotifyService alertNotifyService;
    private final CdcSqlGenerator cdcSqlGenerator;
    private final DatasourceService datasourceService;
    private final ObjectMapper objectMapper;

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
        if (Objects.equals(dto.getSourceConfigId(), dto.getTargetConfigId())) {
            throw new IllegalArgumentException("源数据源和目标数据源不能相同");
        }
        DatasourceConfig source = datasourceService.getDatasource(dto.getSourceConfigId());
        DatasourceConfig target = datasourceService.getDatasource(dto.getTargetConfigId());
        if (taskType == TaskType.cdc_sync) {
            if (source.getDbType() != DatasourceConfig.DbType.mysql && source.getDbType() != DatasourceConfig.DbType.postgresql) {
                throw new IllegalArgumentException("CDC 源数据源只支持 MySQL 或 PostgreSQL");
            }
            if (target.getDbType() != DatasourceConfig.DbType.paimon) {
                throw new IllegalArgumentException("CDC 目标数据源必须是 Paimon");
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
                .sourceConfigId(dto.getSourceConfigId())
                .targetConfigId(dto.getTargetConfigId())
                .flinkSql(dto.getFlinkSql())
                .syncStrategy(syncStrategy)
                .tableMappings(dto.getTableMappings())
                .parallelism(dto.getParallelism() != null ? dto.getParallelism() : 1)
                .checkpointIntervalMs(dto.getCheckpointIntervalMs() != null ? dto.getCheckpointIntervalMs() : 60000L)
                .status(TaskStatus.draft)
                .checkpointCount(0L)
                .build();

        return syncTaskRepository.save(task);
    }

    public SyncTask getTask(Long id) {
        return syncTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    public List<SyncTask> listTasks(TaskStatus status, TaskType taskType, String keyword) {
        return syncTaskRepository.searchTasks(status, taskType, keyword);
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
    public SyncTask updateTask(Long id, SyncTaskUpdateDTO dto) {
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

        return syncTaskRepository.save(task);
    }

    @Transactional
    public void deleteTask(Long id) {
        SyncTask task = getTask(id);
        if (task.getStatus() != TaskStatus.draft && task.getStatus() != TaskStatus.finished) {
            throw new IllegalStateException("只能删除 draft 或 finished 状态的任务，当前状态: " + task.getStatus());
        }
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
    public SyncTask startTask(Long id) {
        SyncTask task = getTask(id);

        // Validate state transition
        if (task.getStatus() != TaskStatus.draft && task.getStatus() != TaskStatus.failed) {
            throw new IllegalStateException("无法启动状态为 " + task.getStatus() + " 的任务");
        }

        // Transition to submitting (intermediate state)
        task.setStatus(TaskStatus.submitting);
        task.setLastErrorMsg(null);
        task.setSubmittedAt(LocalDateTime.now());
        syncTaskRepository.save(task);

        try {
            // Generate CDC SQL dynamically before submission
        if (task.getTaskType() == TaskType.cdc_sync) {
                try {
                    DatasourceConfig sourceConfig = datasourceService.getDatasource(task.getSourceConfigId());
                    DatasourceConfig targetConfig = datasourceService.getDatasource(task.getTargetConfigId());
                    String generatedSql = cdcSqlGenerator.generateCdcSql(task, sourceConfig, targetConfig);
                    task.setFlinkSql(generatedSql);
                    syncTaskRepository.save(task);
                    log.info("CDC SQL generated for task [{}]", task.getTaskName());
                } catch (Exception sqlGenEx) {
                    log.error("Failed to generate CDC SQL for task [{}]: {}", task.getTaskName(), sqlGenEx.getMessage());
                    throw new RuntimeException("CDC SQL 生成失败: " + sqlGenEx.getMessage(), sqlGenEx);
                }
            }

            Map<String, Object> submitResult;

            // Choose submission method based on configuration
            if (flinkClusterService.isSqlGatewayEnabled()) {
                // All task types in this platform are represented as Flink SQL.
                // CDC must also use SQL Gateway; the generated CDC SQL is not an
                // executable user JAR and cannot be submitted through /jars/{id}/run.
                submitResult = flinkClusterService.submitViaSqlGateway(task);
            } else {
                // Compatibility fallback for deployments that provide their own
                // executable job runner JAR.
                submitResult = flinkClusterService.submitJob(task);
            }

            String jobId = (String) submitResult.get("jobId");
            String jarId = (String) submitResult.get("jarId");

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
            // Transition to failed
            task.setStatus(TaskStatus.failed);
            task.setLastErrorMsg("启动失败: " + e.getMessage());
            log.error("Task [{}] start failed: {}", task.getTaskName(), e.getMessage());
            return syncTaskRepository.save(task);
        }
    }

    /**
     * Resume a paused task: paused → submitting → running (from savepoint)
     */
    @Transactional
    public SyncTask resumeTask(Long id) {
        SyncTask task = getTask(id);

        if (task.getStatus() != TaskStatus.paused) {
            throw new IllegalStateException("无法恢复状态为 " + task.getStatus() + " 的任务");
        }

        String savepointPath = extractSavepointPath(task.getCheckpointInfo());
        if (savepointPath == null) {
            throw new IllegalStateException("未找到 savepoint 路径，无法恢复。请从 draft 状态重新启动。");
        }

        // Transition to submitting
        task.setStatus(TaskStatus.submitting);
        task.setSubmittedAt(LocalDateTime.now());
        syncTaskRepository.save(task);

        try {
            Map<String, Object> submitResult;

            if (flinkClusterService.isSqlGatewayEnabled()) {
                submitResult = flinkClusterService.submitViaSqlGateway(task, savepointPath);
            } else {
                submitResult = flinkClusterService.submitFromSavepoint(task, savepointPath);
            }

            String jobId = (String) submitResult.get("jobId");

            task.setStatus(TaskStatus.running);
            task.setFlinkJobId(jobId);
            task.setSubmittedAt(LocalDateTime.now());
            task.setSavepointTriggerId(null);

            log.info("Task [{}] resumed from savepoint: jobId={}, savepoint={}",
                task.getTaskName(), jobId, savepointPath);
            return syncTaskRepository.save(task);

        } catch (Exception e) {
            task.setStatus(TaskStatus.failed);
            task.setLastErrorMsg("恢复失败: " + e.getMessage());
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
        SyncTask task = getTask(id);

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
        SyncTask task = getTask(id);

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
    public SyncTask retryTask(Long id) {
        SyncTask task = getTask(id);

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

        return startTask(id);
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
        SyncTask task = getTask(id);

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

                // Handle Flink state changes
                switch (flinkState) {
                    case "FAILED":
                    case "Failing":
                        handleFlinkJobFailure(task, flinkStatus);
                        break;

                    case "CANCELED":
                    case "FINISHED":
                        handleFlinkJobCompletion(task, flinkState);
                        break;

                    case "RUNNING":
                    case "RESTARTING":
                        updateRunningTaskMetrics(task, flinkStatus);
                        break;

                    default:
                        log.debug("Task [{}] Flink state: {}", task.getTaskName(), flinkState);
                }

                // Check savepoint progress for saving_point tasks
                if (task.getStatus() == TaskStatus.saving_point && task.getSavepointTriggerId() != null) {
                    checkSavepointProgress(task);
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

    private void checkSavepointProgress(SyncTask task) {
        Map<String, Object> spStatus = flinkClusterService.pollSavepointStatus(
            task.getFlinkJobId(), task.getSavepointTriggerId());

        String spProgress = (String) spStatus.get("status");

        if ("COMPLETED".equals(spProgress)) {
            String savepointPath = (String) spStatus.get("savepointPath");

            // Transition to paused
            task.setStatus(TaskStatus.paused);
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
            // Savepoint failed - revert to running
            task.setStatus(TaskStatus.running);
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
