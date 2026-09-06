package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskDefinitionVersion;
import com.rtdwh.entity.TaskDependency;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.service.WorkflowService;
import com.rtdwh.service.WorkflowSqlRunnerService;
import com.rtdwh.service.TaskScheduleService;
import com.rtdwh.service.DatasetProductionService;
import com.rtdwh.service.SyncTaskService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;

@RestController
@RequestMapping("/workflow")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('task:view')")
public class WorkflowController {
    private final WorkflowService workflowService;
    private final WorkflowSqlRunnerService workflowSqlRunnerService;
    private final SecurityContextUtil securityContextUtil;
    private final TaskScheduleService taskScheduleService;
    private final DatasetProductionService datasetProductionService;
    private final SyncTaskService syncTaskService;
    private final com.rtdwh.service.TaskAccessAuditService accessAudit;
    private final com.rtdwh.service.DeliveryFinalizationService deliveryFinalization;

    @GetMapping("/graph")
    public ApiResponse<Map<String, Object>> graph() {
        Set<Long> visible = visibleTaskIds();
        Map<String, Object> graph = workflowService.graph();
        List<SyncTask> tasks = ((List<SyncTask>) graph.get("tasks")).stream()
                .filter(task -> visible.contains(task.getId())).toList();
        List<TaskDependency> dependencies = ((List<TaskDependency>) graph.get("dependencies")).stream()
                .filter(edge -> visible.contains(edge.getUpstreamTaskId()) && visible.contains(edge.getDownstreamTaskId()))
                .toList();
        return ApiResponse.success(Map.of("tasks", tasks, "dependencies", dependencies));
    }

    @PostMapping("/dependencies")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskDependency> addDependency(@Valid @RequestBody WorkflowDTO.DependencyRequest request) {
        authorize(request.getUpstreamTaskId());
        authorize(request.getDownstreamTaskId());
        return ApiResponse.success("依赖创建成功", workflowService.addDependency(
                request.getUpstreamTaskId(), request.getDownstreamTaskId(), securityContextUtil.getCurrentUserId(), request.getConditionType(), request.getOutputDatasetId()));
    }

    @DeleteMapping("/dependencies")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Void> removeDependency(@RequestParam Long upstreamTaskId,
                                              @RequestParam Long downstreamTaskId) {
        authorize(upstreamTaskId);
        authorize(downstreamTaskId);
        workflowService.removeDependency(upstreamTaskId, downstreamTaskId);
        return ApiResponse.success("依赖已删除", null);
    }

    @PostMapping("/tasks/{taskId}/publish")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskDefinitionVersion> publish(@PathVariable Long taskId,
                                                       @Valid @RequestBody WorkflowDTO.PublishRequest request) {
        authorize(taskId);
        return ApiResponse.success("任务版本已发布", workflowService.publish(
                taskId, request.getChangeSummary(), securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/tasks/{taskId}/versions")
    public ApiResponse<List<TaskDefinitionVersion>> versions(@PathVariable Long taskId) {
        authorize(taskId);
        return ApiResponse.success(workflowService.versionsForUser(taskId, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/tasks/{taskId}/rollback/{versionNo}")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> rollback(@PathVariable Long taskId, @PathVariable Integer versionNo) {
        authorize(taskId);
        workflowService.assertRollbackAccess(taskId, versionNo, securityContextUtil.getCurrentUserId());
        return ApiResponse.success("任务配置已回滚", workflowService.rollback(taskId, versionNo));
    }

    @PostMapping("/tasks/{taskId}/backfill")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<List<TaskRunInstance>> backfill(@PathVariable Long taskId,
                                                        @Valid @RequestBody WorkflowDTO.BackfillRequest request) {
        authorize(taskId);
        return ApiResponse.success("补数实例已创建", workflowService.createBackfill(
                taskId, request, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/schedules")
    public ApiResponse<List<com.rtdwh.entity.TaskSchedule>> schedules() {
        Set<Long> visible = visibleTaskIds();
        return ApiResponse.success(taskScheduleService.list().stream()
                .filter(schedule -> visible.contains(schedule.getTaskId())).toList());
    }

    @PutMapping("/tasks/{taskId}/schedule")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<com.rtdwh.entity.TaskSchedule> configureSchedule(
            @PathVariable Long taskId, @Valid @RequestBody WorkflowDTO.ScheduleRequest request) {
        authorize(taskId);
        return ApiResponse.success("周期调度已保存", taskScheduleService.configure(
                taskId, request, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/tasks/{taskId}/schedule/revisions")
    public ApiResponse<List<com.rtdwh.entity.TaskScheduleRevision>> scheduleRevisions(@PathVariable Long taskId) {
        authorize(taskId);
        return ApiResponse.success(taskScheduleService.history(taskId));
    }

    @PutMapping("/tasks/{taskId}/parameters")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<SyncTask> configureParameters(@PathVariable Long taskId, @RequestBody WorkflowDTO.ParameterContractRequest request) {
        authorize(taskId);
        return ApiResponse.success("参数草稿已保存，重新发布后生效", workflowService.configureParameters(taskId, request.getParameterSchemaJson()));
    }

    @GetMapping("/tasks/{taskId}/access-checks")
    public ApiResponse<List<com.rtdwh.entity.TaskAccessCheck>> accessChecks(@PathVariable Long taskId) {
        authorize(taskId);
        return ApiResponse.success(accessAudit.list(taskId));
    }

    @DeleteMapping("/tasks/{taskId}/schedule")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Void> deleteSchedule(@PathVariable Long taskId) {
        authorize(taskId);
        taskScheduleService.delete(taskId, securityContextUtil.getCurrentUserId());
        return ApiResponse.success("周期调度已删除", null);
    }

    @GetMapping("/tasks/{taskId}/outputs")
    public ApiResponse<List<com.rtdwh.entity.TaskOutputDataset>> outputs(@PathVariable Long taskId) {
        authorize(taskId);
        workflowService.getTask(taskId);
        return ApiResponse.success(datasetProductionService.outputs(
                taskId, securityContextUtil.getCurrentUserId()));
    }

    @PutMapping("/tasks/{taskId}/outputs")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<List<com.rtdwh.entity.TaskOutputDataset>> configureOutputs(
            @PathVariable Long taskId,
            @RequestBody List<WorkflowDTO.OutputDatasetRequest> requests) {
        authorize(taskId);
        workflowService.getTask(taskId);
        var result = datasetProductionService.replaceOutputs(taskId, requests, securityContextUtil.getCurrentUserId());
        workflowService.markDraft(taskId);
        return ApiResponse.success("产出草稿已保存，重新发布后对新实例生效", result);
    }

    @GetMapping("/outputs/{outputId}/productions")
    public ApiResponse<List<com.rtdwh.entity.DatasetProduction>> productions(
            @PathVariable Long outputId, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(datasetProductionService.productions(
                outputId, limit, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/instances")
    public ApiResponse<List<TaskRunInstance>> instances(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) TaskRunInstance.RunStatus status,
            @RequestParam(defaultValue = "100") int limit) {
        if (taskId != null) authorize(taskId);
        Set<Long> visible = visibleTaskIds();
        return ApiResponse.success(workflowService.instances(taskId, status, limit).stream()
                .filter(instance -> visible.contains(instance.getTaskId())).toList());
    }

    @GetMapping("/instances/{instanceId}/definition")
    public ApiResponse<TaskDefinitionVersion> instanceDefinition(@PathVariable Long instanceId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        var version = workflowService.definitionForInstance(instanceId);
        workflowService.assertVersionAccess(version, securityContextUtil.getCurrentUserId());
        return ApiResponse.success(version);
    }

    @PostMapping("/instances/claim")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> claim(@RequestParam String executorId, @RequestParam(required = false) Long taskId) {
        if (taskId != null) authorize(taskId);
        return workflowService.claim(executorId, taskId == null ? visibleTaskIds() : Set.of(taskId))
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success("当前没有可执行实例", null));
    }

    @PostMapping("/instances/{instanceId}/complete")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> complete(@PathVariable Long instanceId,
                                                  @Valid @RequestBody WorkflowDTO.CompleteRequest request) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        return ApiResponse.success("实例状态已更新", workflowService.complete(
                instanceId, request.getSuccess(), request.getErrorMessage(), request.getAttemptId(), request.getExecutorId()));
    }

    @PostMapping("/instances/{instanceId}/heartbeat")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> heartbeat(@PathVariable Long instanceId,
                                                   @RequestParam String executorId, @RequestParam(required=false) Long attemptId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        return ApiResponse.success(workflowService.heartbeat(instanceId, executorId, attemptId));
    }

    @PostMapping("/instances/{instanceId}/external-job")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> attachExternalJob(
            @PathVariable Long instanceId,
            @Valid @RequestBody WorkflowDTO.AttachJobRequest request) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        return ApiResponse.success(workflowService.attachExternalJob(
                instanceId, request.getExecutorId(), request.getExternalJobId(), request.getAttemptId()));
    }

    @PostMapping("/instances/{instanceId}/begin-submission")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<Void> beginSubmission(@PathVariable Long instanceId, @RequestParam Long attemptId, @RequestParam String executorId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        workflowService.taskForInstance(workflowService.getInstance(instanceId));
        workflowService.beginSubmission(instanceId, attemptId, executorId);
        return ApiResponse.success(null);
    }

    @GetMapping("/instances/{instanceId}/attempts")
    public ApiResponse<List<com.rtdwh.entity.TaskRunAttempt>> attempts(@PathVariable Long instanceId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        return ApiResponse.success(workflowService.attempts(instanceId));
    }

    @GetMapping("/instances/{instanceId}/bindings")
    public ApiResponse<List<com.rtdwh.entity.TaskRunDependencyBinding>> bindings(@PathVariable Long instanceId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        var visible = visibleTaskIds();
        return ApiResponse.success(workflowService.bindings(instanceId).stream().filter(binding -> visible.contains(binding.getUpstreamTaskId())).toList());
    }

    @PostMapping("/instances/{instanceId}/recheck-delivery")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> recheck(@PathVariable Long instanceId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        workflowService.assertVersionAccess(workflowService.definitionForInstance(instanceId), securityContextUtil.getCurrentUserId());
        return ApiResponse.success(deliveryFinalization.recheck(instanceId));
    }

    @GetMapping("/productions/{productionId}/checks")
    public ApiResponse<List<com.rtdwh.entity.DatasetProductionCheck>> productionChecks(@PathVariable Long productionId) {
        return ApiResponse.success(datasetProductionService.checks(productionId, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/instances/{instanceId}/retry")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> retry(@PathVariable Long instanceId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        return ApiResponse.success("实例已重新入队", workflowService.retryFailed(instanceId));
    }

    @PostMapping("/instances/{instanceId}/cancel")
    @PreAuthorize("hasAuthority('task:manage')")
    public ApiResponse<TaskRunInstance> cancel(@PathVariable Long instanceId) {
        authorize(workflowService.getInstance(instanceId).getTaskId());
        return ApiResponse.success("实例已取消", workflowSqlRunnerService.cancel(instanceId));
    }

    private Set<Long> visibleTaskIds() {
        return syncTaskService.listTasksForUser(securityContextUtil.getCurrentUserId(), null, null, null).stream()
                .map(SyncTask::getId).collect(Collectors.toSet());
    }

    private void authorize(Long taskId) {
        syncTaskService.assertTaskAccess(taskId, securityContextUtil.getCurrentUserId());
    }
}
