package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskDefinitionVersion;
import com.rtdwh.entity.TaskDependency;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.entity.TaskRunInstance.RunStatus;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskDefinitionVersionRepository;
import com.rtdwh.repository.TaskDependencyRepository;
import com.rtdwh.repository.TaskRunInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final SyncTaskRepository taskRepository;
    private final TaskDependencyRepository dependencyRepository;
    private final TaskDefinitionVersionRepository versionRepository;
    private final TaskRunInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;
    private final DatasetProductionService datasetProductionService;
    private final TaskReleaseContractService releaseContracts;
    private final WorkflowDependencyService dependencyBindings;
    private final com.rtdwh.repository.TaskRunAttemptRepository attempts;

    @Value("${workflow.runner.lease-seconds:60}")
    private long leaseSeconds = 60;

    @Value("${workflow.runner.max-retries:3}")
    private int maxRetries = 3;

    @Value("${workflow.runner.retry-backoff-seconds:30}")
    private long retryBackoffSeconds = 30;

    public Map<String, Object> graph() {
        List<SyncTask> tasks = taskRepository.findAll();
        List<TaskDependency> edges = dependencyRepository.findAllByOrderByIdAsc();
        return Map.of("tasks", tasks, "dependencies", edges);
    }

    @Transactional
    public TaskDependency addDependency(Long upstreamId, Long downstreamId, Long userId) {
        return addDependency(upstreamId, downstreamId, userId, "success", null);
    }

    @Transactional
    public TaskDependency addDependency(Long upstreamId, Long downstreamId, Long userId, String condition, Long outputId) {
        if (!Set.of("success", "data_available", "execution_success").contains(condition)) throw new IllegalArgumentException("依赖条件不支持");
        if (Objects.equals(upstreamId, downstreamId)) {
            throw new IllegalArgumentException("任务不能依赖自身");
        }
        SyncTask upstream = requireScheduledTask(upstreamId, "配置工作流依赖");
        SyncTask downstream = requireScheduledTask(downstreamId, "配置工作流依赖");
        if (upstream.getPublishedVersionId() == null || downstream.getPublishedVersionId() == null) {
            throw new IllegalStateException("上下游任务都必须先发布版本再配置依赖");
        }
        if (dependencyRepository.existsByUpstreamTaskIdAndDownstreamTaskId(upstreamId, downstreamId)) {
            throw new IllegalStateException("该依赖关系已存在");
        }
        if (canReach(downstreamId, upstreamId)) {
            throw new IllegalArgumentException("新增依赖会形成环路");
        }
        if ("data_available".equals(condition)) {
            var contract = releaseContracts.forVersion(requirePublishedVersion(upstream));
            var outputs = contract == null ? datasetProductionService.outputs(upstreamId, userId)
                    : contract.outputs().stream().map(TaskReleaseContractService.Output::definition).toList();
            if (outputId == null || outputs.stream().noneMatch(output -> outputId.equals(output.getId())))
                throw new IllegalArgumentException("请选择上游发布版本中声明的产出");
            assertVersionAccess(requirePublishedVersion(upstream), userId);
        }
        downstream.setDefinitionStatus(SyncTask.DefinitionStatus.draft);
        taskRepository.save(downstream);
        return dependencyRepository.save(TaskDependency.builder()
                .upstreamTaskId(upstreamId)
                .downstreamTaskId(downstreamId)
                .conditionType(condition).outputDatasetId(outputId)
                .createdAt(LocalDateTime.now())
                .createdBy(userId)
                .build());
    }

    @Transactional
    public void removeDependency(Long upstreamId, Long downstreamId) {
        dependencyRepository.deleteByUpstreamTaskIdAndDownstreamTaskId(upstreamId, downstreamId);
        markDraft(downstreamId);
    }

    @Transactional
    public void markDraft(Long taskId) {
        SyncTask task = requireTask(taskId);
        task.setDefinitionStatus(SyncTask.DefinitionStatus.draft);
        taskRepository.save(task);
    }

    @Transactional
    public TaskDefinitionVersion publish(Long taskId, String summary, Long userId) {
        SyncTask task = requireScheduledTask(taskId, "发布版本");
        int nextVersion = versionRepository.findFirstByTaskIdOrderByVersionNoDesc(taskId)
                .map(item -> item.getVersionNo() + 1).orElse(1);
        try {
            SyncTask snapshot = objectMapper.readValue(objectMapper.writeValueAsString(task), SyncTask.class);
            releaseContracts.preparePublication(snapshot);
            String taskJson = objectMapper.writeValueAsString(snapshot);
            String contractJson = releaseContracts.snapshot(taskId);
            TaskDefinitionVersion version = versionRepository.saveAndFlush(TaskDefinitionVersion.builder()
                    .taskId(taskId)
                    .versionNo(nextVersion)
                    .changeSummary(summary.trim())
                    .snapshotJson(taskJson)
                    .contractJson(contractJson)
                    .contractHash(TaskReleaseContractService.fingerprint(taskJson, contractJson))
                    .createdBy(userId)
                    .createdAt(LocalDateTime.now())
                    .build());
            task.setPublishedVersionId(version.getId());
            task.setDefinitionStatus(SyncTask.DefinitionStatus.published);
            taskRepository.save(task);
            return version;
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("任务版本并发冲突，请重试");
        } catch (Exception exception) {
            throw new IllegalStateException("任务版本快照生成失败: " + exception.getMessage());
        }
    }

    @Transactional
    public SyncTask configureParameters(Long taskId, String schema) {
        SyncTask task = requireScheduledTask(taskId, "配置运行参数");
        task.setParameterSchemaJson(new TaskParameterService(objectMapper).validateTemplate(task.getFlinkSql(), schema));
        task.setDefinitionStatus(SyncTask.DefinitionStatus.draft);
        return taskRepository.save(task);
    }

    public String validateScheduleParameters(Long taskId, String parameters) {
        return releaseContracts.parametersForVersion(requirePublishedVersion(requireTask(taskId)), parameters);
    }

    public List<TaskDefinitionVersion> versions(Long taskId) {
        requireScheduledTask(taskId, "查看版本");
        return versionRepository.findByTaskIdOrderByVersionNoDesc(taskId);
    }

    public List<TaskDefinitionVersion> versionsForUser(Long taskId, Long userId) {
        return versions(taskId).stream().filter(version -> releaseContracts.canReadVersion(version, userId)).toList();
    }

    public void assertVersionAccess(TaskDefinitionVersion version, Long userId) {
        if (!releaseContracts.canReadVersion(version, userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该发布版本涉及的数据");
        }
    }

    public void assertRollbackAccess(Long taskId, Integer versionNo, Long userId) {
        assertVersionAccess(versionRepository.findByTaskIdAndVersionNo(taskId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("任务版本不存在")), userId);
    }

    @Transactional
    public SyncTask rollback(Long taskId, Integer versionNo) {
        SyncTask task = requireScheduledTask(taskId, "回滚版本");
        if (task.getStatus() != SyncTask.TaskStatus.draft) {
            throw new IllegalStateException("只有 draft 状态任务可以回滚配置");
        }
        TaskDefinitionVersion version = versionRepository.findByTaskIdAndVersionNo(taskId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("任务版本不存在: " + versionNo));
        try {
            JsonNode snapshot = objectMapper.readTree(version.getSnapshotJson());
            task.setTaskName(text(snapshot, "taskName", task.getTaskName()));
            task.setDescription(nullableText(snapshot, "description"));
            task.setFlinkSql(nullableText(snapshot, "flinkSql"));
            task.setParameterSchemaJson(nullableText(snapshot, "parameterSchemaJson"));
            task.setTableMappings(nullableText(snapshot, "tableMappings"));
            task.setParallelism(snapshot.path("parallelism").asInt(task.getParallelism()));
            task.setCheckpointIntervalMs(snapshot.path("checkpointIntervalMs").asLong(task.getCheckpointIntervalMs()));
            task.setDefinitionStatus(SyncTask.DefinitionStatus.draft);
            return taskRepository.save(task);
        } catch (Exception exception) {
            throw new IllegalStateException("任务版本快照损坏，无法回滚");
        }
    }

    @Transactional
    public List<TaskRunInstance> createBackfill(Long taskId, WorkflowDTO.BackfillRequest request, Long userId) {
        SyncTask task = requireScheduledTask(taskId, "补数");
        TaskDefinitionVersion publishedVersion = requirePublishedVersion(task);
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (days > 366) {
            throw new IllegalArgumentException("单次补数最多创建 366 个实例");
        }
        String policy = request.getBindingPolicy() == null ? "batch_only" : request.getBindingPolicy();
        if (!Set.of("batch_only", "reuse_available").contains(policy)) throw new IllegalArgumentException("补数依赖策略不支持");
        Map<Long, TaskDefinitionVersion> selected = new LinkedHashMap<>();
        collectBackfillVersions(publishedVersion, selected, new HashSet<>(), "batch_only".equals(policy), userId);
        if (selected.size() * days > 2000) throw new IllegalArgumentException("单次补数含上游最多创建 2000 个实例");
        Map<Long, String> parameters = request.getTaskParametersJson() == null ? Map.of() : request.getTaskParametersJson();
        if (!selected.keySet().containsAll(parameters.keySet())) throw new IllegalArgumentException("参数包含本批以外的任务");
        String batchId = UUID.randomUUID().toString();
        List<TaskRunInstance> runs = new ArrayList<>();
        for (var entry : selected.entrySet()) for (int offset = 0; offset < days; offset++) {
            LocalDate date = request.getStartDate().plusDays(offset);
            String supplied = entry.getKey().equals(taskId) ? request.getParametersJson() : parameters.get(entry.getKey());
            runs.add(TaskRunInstance.builder().taskId(entry.getKey()).definitionVersionId(entry.getValue().getId())
                    .batchId(batchId).businessDate(date).windowStart(date).windowEnd(date.plusDays(1)).bindingPolicy(policy)
                    .triggerType("backfill").status(releaseContracts.dependencies(entry.getValue()).isEmpty() ? RunStatus.queued : RunStatus.waiting)
                    .parametersJson(releaseContracts.parametersForVersion(entry.getValue(), supplied))
                    .createdBy(userId).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        }
        runs = instanceRepository.saveAll(runs);
        instanceRepository.flush();
        for (TaskRunInstance run : runs) dependencyBindings.initialize(run, selected);
        return runs;
    }

    private void collectBackfillVersions(TaskDefinitionVersion version, Map<Long, TaskDefinitionVersion> selected,
                                        Set<Long> visiting, boolean includeUpstream, Long actor) {
        if (!visiting.add(version.getTaskId())) throw new IllegalArgumentException("发布依赖存在环路");
        if (selected.containsKey(version.getTaskId())) { visiting.remove(version.getTaskId()); return; }
        assertVersionAccess(version, actor);
        if (selected.size() + visiting.size() > 100) throw new IllegalArgumentException("补数依赖规模超过限制");
        if (includeUpstream) for (TaskDependency dependency : releaseContracts.dependencies(version)) {
            collectBackfillVersions(requirePublishedVersion(requireScheduledTask(dependency.getUpstreamTaskId(), "补数")), selected, visiting, true, actor);
        }
        selected.put(version.getTaskId(), version); visiting.remove(version.getTaskId());
    }

    @Transactional
    public TaskRunInstance createScheduledInstance(Long taskId, Long scheduleId, java.time.Instant scheduledAt,
                                                   LocalDate businessDate, String parametersJson, Long userId) {
        return createScheduledInstance(taskId, scheduleId, scheduledAt, businessDate, parametersJson, userId, null);
    }

    @Transactional
    public TaskRunInstance createScheduledInstance(Long taskId, Long scheduleId, java.time.Instant scheduledAt,
                                                   LocalDate businessDate, String parametersJson, Long userId, Long revisionId) {
        SyncTask task = requireScheduledTask(taskId, "周期调度");
        TaskDefinitionVersion publishedVersion = requirePublishedVersion(task);
        boolean hasUpstream = !releaseContracts.dependencies(publishedVersion).isEmpty();
        TaskRunInstance run = instanceRepository.saveAndFlush(TaskRunInstance.builder()
                .taskId(taskId)
                .definitionVersionId(publishedVersion.getId())
                .scheduleRevisionId(revisionId).scheduledAt(scheduledAt)
                .windowStart(businessDate).windowEnd(businessDate.plusDays(1)).bindingPolicy("reuse_available")
                .batchId("schedule-" + scheduleId + "-" + scheduledAt.toEpochMilli())
                .businessDate(businessDate)
                .triggerType("schedule")
                .status(hasUpstream ? RunStatus.waiting : RunStatus.queued)
                .parametersJson(releaseContracts.parametersForVersion(publishedVersion, parametersJson))
                .createdBy(userId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        dependencyBindings.initialize(run, Map.of());
        return run;
    }

    public List<TaskRunInstance> instances(Long taskId, RunStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        PageRequest page = PageRequest.of(0, safeLimit);
        if (taskId != null && status != null) {
            return instanceRepository.findByTaskIdAndStatusOrderByCreatedAtDesc(taskId, status, page);
        }
        if (taskId != null) return instanceRepository.findByTaskIdOrderByCreatedAtDesc(taskId, page);
        if (status != null) return instanceRepository.findByStatusOrderByCreatedAtAsc(status, page);
        return instanceRepository.findAll(PageRequest.of(0, safeLimit,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Transactional
    public Optional<TaskRunInstance> claim(String executorId) {
        return claim(executorId, null);
    }

    @Transactional
    public Optional<TaskRunInstance> claim(String executorId, Set<Long> allowedTaskIds) {
        if (executorId == null || executorId.isBlank()) {
            throw new IllegalArgumentException("executorId 不能为空");
        }
        if (allowedTaskIds != null && allowedTaskIds.isEmpty()) return Optional.empty();
        LocalDateTime now = LocalDateTime.now();
        List<TaskRunInstance> runnable = allowedTaskIds == null
                ? instanceRepository.findRunnableForUpdate(RunStatus.queued, now, PageRequest.of(0, 1))
                : instanceRepository.findRunnableForTaskIdsForUpdate(
                        RunStatus.queued, now, allowedTaskIds, PageRequest.of(0, 1));
        Optional<TaskRunInstance> candidate = runnable.stream().filter(instance -> {
            if (dependencyBindings.ready(instance)) return true;
            instance.setStatus(RunStatus.waiting); instanceRepository.save(instance); return false;
        }).findFirst();
        candidate.ifPresent(instance -> {
            int number = (instance.getAttemptCount() == null ? 0 : instance.getAttemptCount()) + 1;
            var attempt = attempts.saveAndFlush(com.rtdwh.entity.TaskRunAttempt.builder().instanceId(instance.getId()).attemptNo(number)
                    .executorId(executorId.trim()).status("claimed").startedAt(now).build());
            instance.setActiveAttemptId(attempt.getId()); instance.setAttemptCount(number);
            instance.setStatus(RunStatus.running);
            instance.setExecutorId(executorId.trim());
            instance.setStartedAt(now);
            instance.setFinishedAt(null);
            instance.setHeartbeatAt(now);
            instance.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
            instance.setNextRetryAt(null);
            instance.setUpdatedAt(now);
            instanceRepository.save(instance);
        });
        return candidate;
    }

    public List<com.rtdwh.entity.TaskRunAttempt> attempts(Long id) { return attempts.findByInstanceIdOrderByAttemptNoDesc(id); }
    public List<com.rtdwh.entity.TaskRunDependencyBinding> bindings(Long id) { return dependencyBindings.bindings(id); }

    @Transactional
    public void beginSubmission(Long id, Long attemptId, String executor) {
        TaskRunInstance run = requireRunningInstance(id, executor);
        fence(run, attemptId, executor);
        var attempt = attempts.findById(attemptId).orElseThrow();
        if (!"claimed".equals(attempt.getStatus())) throw new IllegalStateException("本次执行已提交或结果待核对，禁止重复提交");
        dependencyBindings.assertAccess(run);
        if (!dependencyBindings.ready(run)) throw new IllegalStateException("已绑定的上游交付当前不可用，禁止提交");
        attempt.setStatus("submitting"); attempt.setSubmittedAt(LocalDateTime.now()); attempts.save(attempt);
    }

    @Transactional
    public TaskRunInstance attachExternalJob(Long id, String executor, String job, Long attemptId) {
        TaskRunInstance run = requireRunningInstance(id, executor); fence(run, attemptId, executor);
        if (job == null || job.isBlank()) throw new IllegalArgumentException("externalJobId 不能为空");
        if (run.getExternalJobId() != null && !run.getExternalJobId().equals(job)) throw new IllegalStateException("本次执行已绑定另一 Job");
        if (attemptId != null) {
            var attempt = attempts.findById(attemptId).orElseThrow();
            if (attempt.getSubmittedAt() == null) throw new IllegalStateException("请先登记提交意图再绑定引擎 Job");
            attempt.setExternalJobId(job.trim()); attempt.setStatus("running");
            attempts.save(attempt);
        }
        run.setExternalJobId(job.trim()); touch(run);
        return instanceRepository.save(run);
    }

    @Transactional
    public TaskRunInstance heartbeat(Long id, String executor, Long attemptId) {
        TaskRunInstance run = requireRunningInstance(id, executor); fence(run, attemptId, executor); touch(run);
        return instanceRepository.save(run);
    }

    @Transactional
    public TaskRunInstance complete(Long id, boolean success, String error) { return complete(id, success, error, null, null); }

    @Transactional
    public TaskRunInstance complete(Long id, boolean success, String error, Long attemptId, String executor) {
        TaskRunInstance run = locked(id); fence(run, attemptId, executor);
        RunStatus terminal = success ? RunStatus.success : RunStatus.failed;
        if (run.getStatus() == terminal) return run; // Same attempt and outcome: idempotent callback.
        if (run.getStatus() != RunStatus.running) throw new IllegalStateException("实例状态不接受该完成回调");
        if (success && attemptId != null && run.getExternalJobId() == null) throw new IllegalStateException("成功回调缺少已绑定的引擎作业");
        finishAttempt(run, success ? "success" : "failed", error);
        run.setStatus(terminal); run.setDeliveryStatus(success ? "checking" : "pending");
        // Existing run timestamps use MySQL DATETIME (whole seconds). Return the
        // same precision on the first callback and subsequent persisted reads.
        run.setErrorMessage(success ? null : trimError(error)); run.setFinishedAt(LocalDateTime.now().withNano(0));
        run.setHeartbeatAt(null); run.setLeaseExpiresAt(null); run.setNextRetryAt(null); run.setUpdatedAt(LocalDateTime.now());
        return instanceRepository.save(run);
    }

    @Transactional
    public TaskRunInstance submissionUnknown(Long id, Long attemptId, String executor) {
        TaskRunInstance run = locked(id); fence(run, attemptId, executor);
        if (run.getStatus() != RunStatus.running) return run;
        if (attemptId != null) {
            var attempt = attempts.findById(attemptId).orElseThrow();
            attempt.setStatus("unknown"); attempt.setErrorMessage("提交或引擎结果未确认，禁止自动重放"); attempts.save(attempt);
        }
        run.setErrorMessage("执行结果待核对，禁止自动重放"); touch(run); return instanceRepository.save(run);
    }

    @Transactional
    public TaskRunInstance failOrRetry(Long id, String error) { return failOrRetry(id, error, null, null); }

    @Transactional
    public TaskRunInstance failOrRetry(Long id, String error, Long attemptId, String executor) {
        TaskRunInstance run = locked(id); fence(run, attemptId, executor);
        if (run.getStatus() != RunStatus.running) return run;
        boolean safe = safeBeforeSubmission(run);
        finishAttempt(run, "failed", error);
        int retries = run.getRetryCount() == null ? 0 : run.getRetryCount();
        run.setErrorMessage(trimError(error)); run.setHeartbeatAt(null); run.setLeaseExpiresAt(null);
        if (safe && retries < maxRetries) {
            run.setRetryCount(retries + 1); run.setStatus(RunStatus.queued);
            run.setStartedAt(null); run.setFinishedAt(null); run.setExecutorId(null); run.setExternalJobId(null);
            run.setNextRetryAt(LocalDateTime.now().plusSeconds(retryDelaySeconds(retries + 1)));
        } else {
            run.setStatus(RunStatus.failed); run.setFinishedAt(LocalDateTime.now()); run.setNextRetryAt(null);
        }
        run.setUpdatedAt(LocalDateTime.now()); return instanceRepository.save(run);
    }

    @Transactional
    public TaskRunInstance retryFailed(Long id) {
        TaskRunInstance run = locked(id);
        if (run.getStatus() != RunStatus.failed) throw new IllegalStateException("只有 failed 实例可以重试");
        if (!safeBeforeSubmission(run)) throw new IllegalStateException("执行已提交或历史写入无法确认；当前写入模式未验证可安全重放，禁止直接重试");
        run.setStatus(RunStatus.queued); run.setRetryCount(0); run.setErrorMessage(null);
        run.setExecutorId(null); run.setExternalJobId(null); run.setStartedAt(null); run.setFinishedAt(null);
        run.setHeartbeatAt(null); run.setLeaseExpiresAt(null); run.setNextRetryAt(null); run.setUpdatedAt(LocalDateTime.now());
        return instanceRepository.save(run);
    }

    @Transactional
    public TaskRunInstance cancel(Long id) {
        TaskRunInstance run = locked(id);
        if (Set.of(RunStatus.success, RunStatus.failed, RunStatus.cancelled).contains(run.getStatus())) throw new IllegalStateException("终态实例不能取消");
        if (run.getStatus() == RunStatus.running && run.getExternalJobId() == null && !safeBeforeSubmission(run))
            throw new IllegalStateException("提交结果未知，请先核对并绑定引擎 Job，不能把未知写入标为已取消");
        finishAttempt(run, "cancelled", "用户取消");
        run.setStatus(RunStatus.cancelled); run.setFinishedAt(LocalDateTime.now()); run.setHeartbeatAt(null); run.setLeaseExpiresAt(null);
        run.setNextRetryAt(null); run.setUpdatedAt(LocalDateTime.now()); return instanceRepository.save(run);
    }

    @Transactional
    public int recoverExpiredInstances() {
        var expired = instanceRepository.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(RunStatus.running, LocalDateTime.now(), PageRequest.of(0, 200));
        int recovered = 0;
        for (TaskRunInstance candidate : expired) {
            TaskRunInstance run = locked(candidate.getId());
            if (run.getStatus() != RunStatus.running || run.getLeaseExpiresAt() == null
                    || !run.getLeaseExpiresAt().isBefore(LocalDateTime.now())) continue;
            if (safeBeforeSubmission(run)) failOrRetry(run.getId(), "提交前执行器租约到期", run.getActiveAttemptId(), run.getExecutorId());
            else submissionUnknown(run.getId(), run.getActiveAttemptId(), run.getExecutorId());
            recovered++;
        }
        return recovered;
    }

    private TaskRunInstance locked(Long id) {
        return instanceRepository.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("运行实例不存在"));
    }
    private void fence(TaskRunInstance run, Long attemptId, String executor) {
        if (run.getActiveAttemptId() != null && (!run.getActiveAttemptId().equals(attemptId) || !Objects.equals(run.getExecutorId(), executor)))
            throw new IllegalStateException("执行尝试已变更或执行器不匹配，拒绝过期回调");
    }
    private void touch(TaskRunInstance run) {
        run.setHeartbeatAt(LocalDateTime.now()); run.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(leaseSeconds)); run.setUpdatedAt(LocalDateTime.now());
    }
    private boolean safeBeforeSubmission(TaskRunInstance run) {
        if (run.getActiveAttemptId() == null) return false; // Legacy provenance cannot prove that no write occurred.
        var attempt = attempts.findById(run.getActiveAttemptId()).orElseThrow();
        return attempt.getSubmittedAt() == null && attempt.getExternalJobId() == null;
    }
    private void finishAttempt(TaskRunInstance run, String status, String error) {
        if (run.getActiveAttemptId() == null) return;
        var attempt = attempts.findById(run.getActiveAttemptId()).orElseThrow();
        attempt.setStatus(status); attempt.setFinishedAt(LocalDateTime.now());
        attempt.setErrorMessage(error == null ? null : error.substring(0, Math.min(512, error.length()))); attempts.save(attempt);
    }

    public List<TaskRunInstance> runningByExecutor(String executorId, int limit) {
        return instanceRepository.findByStatusAndExecutorIdOrderByUpdatedAtAsc(
                RunStatus.running, executorId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    public SyncTask taskForInstance(TaskRunInstance instance) {
        if (instance.getDefinitionVersionId() == null) {
            throw new IllegalStateException("运行实例未绑定任务版本，请重新创建实例");
        }
        TaskDefinitionVersion version = versionRepository.findById(instance.getDefinitionVersionId())
                .filter(item -> Objects.equals(item.getTaskId(), instance.getTaskId()))
                .orElseThrow(() -> new IllegalStateException("运行实例绑定的任务版本不存在"));
        try {
            releaseContracts.forVersion(version);
            SyncTask snapshot = objectMapper.readValue(version.getSnapshotJson(), SyncTask.class);
            snapshot.setId(instance.getTaskId());
            if (snapshot.getExecutionMode() == null) snapshot.setExecutionMode(SyncTask.ExecutionMode.scheduled);
            releaseContracts.validateExecution(instance, snapshot);
            instance.setAccessCheckedAt(LocalDateTime.now());
            if (instance.getId() != null) instanceRepository.recordAccessCheckedAt(instance.getId(), instance.getAccessCheckedAt());
            return snapshot;
        } catch (IllegalArgumentException denied) {
            throw denied;
        } catch (Exception exception) {
            throw new IllegalStateException("任务版本校验失败，无法执行", exception);
        }
    }

    public TaskRunInstance getInstance(Long instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("运行实例不存在: " + instanceId));
    }

    public SyncTask getTask(Long taskId) { return requireTask(taskId); }

    public TaskDefinitionVersion definitionForInstance(Long instanceId) {
        TaskRunInstance instance = getInstance(instanceId);
        if (instance.getDefinitionVersionId() == null) {
            throw new IllegalStateException("运行实例未绑定任务版本");
        }
        return versionRepository.findById(instance.getDefinitionVersionId())
                .filter(item -> Objects.equals(item.getTaskId(), instance.getTaskId()))
                .orElseThrow(() -> new IllegalStateException("运行实例绑定的任务版本不存在"));
    }

    @Transactional
    public int promoteReadyInstances() {
        List<TaskRunInstance> waiting = instanceRepository.findByStatusOrderByCreatedAtAsc(
                RunStatus.waiting, PageRequest.of(0, 200));
        int promoted = 0;
        for (TaskRunInstance instance : waiting) {
            instance = locked(instance.getId());
            if (instance.getStatus() != RunStatus.waiting) continue;
            boolean ready = dependencyBindings.ready(instance);
            if (ready) {
                instance.setStatus(RunStatus.queued);
                instance.setUpdatedAt(LocalDateTime.now());
                instanceRepository.save(instance);
                promoted++;
            }
        }
        return promoted;
    }

    private boolean canReach(Long start, Long target) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (TaskDependency dependency : dependencyRepository.findAll()) {
            adjacency.computeIfAbsent(dependency.getUpstreamTaskId(), ignored -> new ArrayList<>())
                    .add(dependency.getDownstreamTaskId());
        }
        Deque<Long> pending = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Long current = pending.removeFirst();
            if (Objects.equals(current, target)) return true;
            if (visited.add(current)) pending.addAll(adjacency.getOrDefault(current, List.of()));
        }
        return false;
    }

    private SyncTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }

    private SyncTask requireScheduledTask(Long taskId, String action) {
        SyncTask task = requireTask(taskId);
        if (task.getExecutionMode() != SyncTask.ExecutionMode.scheduled) {
            throw new IllegalArgumentException("只有周期任务支持" + action);
        }
        return task;
    }

    private TaskDefinitionVersion requirePublishedVersion(SyncTask task) {
        if (task.getPublishedVersionId() == null) {
            throw new IllegalStateException("请先发布任务版本再创建运行实例");
        }
        return versionRepository.findById(task.getPublishedVersionId())
                .filter(version -> Objects.equals(version.getTaskId(), task.getId()))
                .orElseThrow(() -> new IllegalStateException("任务发布版本不存在，请重新发布"));
    }

    private TaskRunInstance requireRunningInstance(Long instanceId, String executorId) {
        TaskRunInstance instance = locked(instanceId);
        if (instance.getStatus() != RunStatus.running) {
            throw new IllegalStateException("实例不是 running 状态: " + instance.getStatus());
        }
        if (executorId == null || !executorId.trim().equals(instance.getExecutorId())) {
            throw new IllegalStateException("实例已由其他执行器持有");
        }
        return instance;
    }

    private long retryDelaySeconds(int retryNumber) {
        long multiplier = 1L << Math.min(Math.max(retryNumber - 1, 0), 10);
        return Math.max(1, retryBackoffSeconds) * multiplier;
    }

    private String trimError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return "任务执行失败";
        String value = errorMessage.trim();
        return value.length() > 4000 ? value.substring(0, 4000) : value;
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = nullableText(node, field);
        return value == null ? fallback : value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
