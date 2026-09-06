package com.rtdwh.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.FlinkJobScaleRequest;
import com.rtdwh.dto.SyncTaskCreateDTO;
import com.rtdwh.dto.SyncTaskUpdateDTO;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.SyncTask.TaskStatus;
import com.rtdwh.entity.SyncTask.TaskType;
import com.rtdwh.service.CdcSqlGenerator;
import com.rtdwh.service.DatasourceService;
import com.rtdwh.service.SyncTaskService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/sync-tasks")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('task:view')")
public class SyncTaskController {

    private final SyncTaskService syncTaskService;
    private final CdcSqlGenerator cdcSqlGenerator;
    private final DatasourceService datasourceService;
    private final SecurityContextUtil securityContextUtil;
    private final ObjectMapper objectMapper;
    private final com.rtdwh.service.ContinuousDeploymentService continuousDeployments;

    // ========================================================================
    // CRUD
    // ========================================================================

    @GetMapping
    public ApiResponse<List<SyncTask>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String keyword) {

        TaskStatus statusEnum = status != null ? TaskStatus.valueOf(status) : null;
        TaskType typeEnum = taskType != null ? TaskType.valueOf(taskType) : null;

        List<SyncTask> tasks = syncTaskService.listTasksForUser(
                securityContextUtil.getCurrentUserId(), statusEnum, typeEnum, keyword);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/{id}")
    public ApiResponse<SyncTask> getTask(@PathVariable Long id) {
        return ApiResponse.success(syncTaskService.getTaskForUser(id, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('task:create')")
    public ApiResponse<SyncTask> createTask(@Valid @RequestBody SyncTaskCreateDTO dto) {
        Long creatorId = securityContextUtil.getCurrentUserId();
        return ApiResponse.success("任务创建成功", syncTaskService.createTask(dto, creatorId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> updateTask(@PathVariable Long id, @RequestBody SyncTaskUpdateDTO dto) {
        authorize(id);
        return ApiResponse.success("任务配置已更新", syncTaskService.updateTask(
                id, dto, securityContextUtil.getCurrentUserId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Void> deleteTask(@PathVariable Long id) {
        authorize(id);
        syncTaskService.deleteTask(id);
        return ApiResponse.success("任务已删除", null);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<com.rtdwh.entity.TaskDefinitionVersion> publish(@PathVariable Long id,
            @Valid @RequestBody com.rtdwh.dto.WorkflowDTO.PublishRequest request) {
        authorize(id);
        return ApiResponse.success("持续任务版本已发布", syncTaskService.publishContinuous(id,
                securityContextUtil.getCurrentUserId(), request.getChangeSummary()));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<com.rtdwh.entity.TaskDefinitionVersion>> versions(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.success(continuousDeployments.versions(id, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/{id}/deployments")
    public ApiResponse<List<com.rtdwh.entity.TaskDeploymentRevision>> deployments(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.success(continuousDeployments.deployments(id, securityContextUtil.getCurrentUserId()));
    }

    // ========================================================================
    // Task Lifecycle Actions
    // ========================================================================

    /**
     * 启动任务 (draft/failed → running)
     */
    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> startTask(@PathVariable Long id) {
        authorize(id);
        SyncTask task = syncTaskService.startTask(id, securityContextUtil.getCurrentUserId());
        String msg = task.getStatus() == TaskStatus.running ? "任务已启动" : "任务启动失败";
        return ApiResponse.success(msg, task);
    }

    /**
     * 暂停任务 (running → saving_point → paused)
     * 注意：暂停是异步操作，先触发 Savepoint，中间状态为 saving_point
     */
    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> pauseTask(@PathVariable Long id) {
        authorize(id);
        SyncTask task = syncTaskService.pauseTask(id);
        String msg = task.getStatus() == TaskStatus.saving_point
            ? "正在创建 Savepoint，请等待..." : "任务暂停中";
        return ApiResponse.success(msg, task);
    }

    /**
     * 恢复任务 (paused → running, 从 savepoint 恢复)
     */
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> resumeTask(@PathVariable Long id) {
        authorize(id);
        SyncTask task = syncTaskService.resumeTask(id, securityContextUtil.getCurrentUserId());
        String msg = task.getStatus() == TaskStatus.running ? "任务已从 Savepoint 恢复" : "任务恢复失败";
        return ApiResponse.success(msg, task);
    }

    /**
     * 停止任务 (任意活跃状态 → finished，不保留 Savepoint)
     */
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> stopTask(@PathVariable Long id) {
        authorize(id);
        SyncTask task = syncTaskService.stopTask(id);
        return ApiResponse.success("任务已停止（未保留 Savepoint）", task);
    }

    /**
     * 重试失败任务 (failed → running)
     */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> retryTask(@PathVariable Long id) {
        authorize(id);
        SyncTask task = syncTaskService.retryTask(id, securityContextUtil.getCurrentUserId());
        String msg = task.getStatus() == TaskStatus.running ? "任务重试成功" : "任务重试失败";
        return ApiResponse.success(msg, task);
    }

    @GetMapping("/{id}/postgres-cdc-status")
    public ApiResponse<Map<String, Object>> getPostgresCdcStatus(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.success(syncTaskService.getPostgresCdcStatus(id));
    }

    @PostMapping("/{id}/cleanup-postgres-cdc")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Map<String, Object>> cleanupPostgresCdc(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.success("PostgreSQL CDC 资源已清理", syncTaskService.cleanupPostgresCdcResources(id));
    }

    /**
     * 手动触发 Savepoint (不停止任务)
     */
    @PostMapping("/{id}/savepoint")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> triggerSavepoint(@PathVariable Long id) {
        authorize(id);
        SyncTask task = syncTaskService.triggerManualSavepoint(id);
        return ApiResponse.success("Savepoint 触发成功，请轮询状态查看进度", task);
    }

    // ========================================================================
    // Status & Monitoring
    // ========================================================================

    /**
     * 获取任务详细状态（含 Flink 实时指标）
     */
    @GetMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> getTaskStatus(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.success(syncTaskService.getTaskStatus(id));
    }

    /** Fresh adaptive-scaling capability and per-vertex parallelism. */
    @GetMapping("/{id}/scaling")
    public ApiResponse<Map<String, Object>> getTaskScaling(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.success(syncTaskService.getTaskScaling(id));
    }

    /** Re-declare a running streaming job's resource requirements. */
    @PostMapping("/{id}/scale")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Map<String, Object>> rescaleTask(
            @PathVariable Long id,
            @Valid @RequestBody FlinkJobScaleRequest request
    ) {
        authorize(id);
        return ApiResponse.success(
                "Flink 已接受并行度调整请求",
                syncTaskService.rescaleTask(
                        id,
                        request.getTargetParallelism(),
                        request.getExpectedJobId(),
                        request.getExpectedConfiguredParallelism(),
                        request.getReason(),
                        securityContextUtil.getCurrentUsername()
                )
        );
    }

    /**
     * 获取任务日志 (JobManager / TaskManager)
     */
    @GetMapping("/{id}/logs")
    public ApiResponse<Map<String, Object>> getTaskLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "jobmanager") String type,
            @RequestParam(defaultValue = "200") int lines) {

        authorize(id);

        Map<String, Object> logs = syncTaskService.getTaskLogs(id, type, lines);
        return ApiResponse.success(logs);
    }

    /**
     * 手动触发状态同步（从 Flink 集群刷新所有活跃任务状态）
     */
    @PostMapping("/sync-status")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Integer> syncAllTaskStatus() {
        int synced = syncTaskService.syncTaskStatusFromFlink();
        return ApiResponse.success("同步完成", synced);
    }

    /**
     * Preview CDC SQL for a task configuration (without saving).
     */
    @PostMapping("/preview-cdc-sql")
    @PreAuthorize("hasAuthority('task:create')")
    public ApiResponse<Map<String, Object>> previewCdcSql(@RequestBody Map<String, Object> config) {
        try {
            // Build a temporary SyncTask from the config
            SyncTask task = new SyncTask();
            task.setTaskName((String) config.getOrDefault("taskName", "preview"));
            task.setScenarioCode((String) config.getOrDefault("scenarioCode", "table_realtime_sync"));
            task.setTaskType(SyncTask.TaskType.valueOf((String) config.getOrDefault("taskType", "cdc_sync")));
            task.setSyncStrategy(SyncTask.SyncStrategy.valueOf((String) config.getOrDefault("syncStrategy", "full_then_incremental")));
            task.setParallelism(Integer.parseInt(String.valueOf(config.getOrDefault("parallelism", 1))));
            task.setCheckpointIntervalMs(Long.parseLong(String.valueOf(config.getOrDefault("checkpointIntervalMs", 60000))));
            Object mappings = config.get("tableMappings");
            task.setTableMappings(mappings instanceof String ? (String) mappings : objectMapper.writeValueAsString(mappings));

            // Resolve source and target datasource configs
            Long sourceConfigId = config.get("sourceConfigId") != null
                ? Long.parseLong(String.valueOf(config.get("sourceConfigId"))) : null;
            if (sourceConfigId == null) return ApiResponse.error(400, "预览需要提供 sourceConfigId");

            DatasourceConfig sourceConfig = datasourceService.getDatasource(sourceConfigId);
            String sql = cdcSqlGenerator.generateCdcSql(task, sourceConfig);
            return ApiResponse.success(Map.of("sql", sql, "preview", true));
        } catch (Exception e) {
            log.error("Failed to preview CDC SQL: {}", e.getMessage());
            return ApiResponse.error(400, "预览失败: " + e.getMessage());
        }
    }

    private void authorize(Long taskId) {
        syncTaskService.assertTaskAccess(taskId, securityContextUtil.getCurrentUserId());
    }
}
