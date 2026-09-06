package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.TaskDefinitionVersion;
import com.rtdwh.entity.TaskDependency;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskDefinitionVersionRepository;
import com.rtdwh.repository.TaskDependencyRepository;
import com.rtdwh.repository.TaskRunInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowServiceTest {
    private final SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
    private final TaskDependencyRepository dependencyRepository = mock(TaskDependencyRepository.class);
    private final TaskDefinitionVersionRepository versionRepository = mock(TaskDefinitionVersionRepository.class);
    private final TaskRunInstanceRepository instanceRepository = mock(TaskRunInstanceRepository.class);
    private final DatasetProductionService datasetProductionService = mock(DatasetProductionService.class);
    private final TaskReleaseContractService contracts = mock(TaskReleaseContractService.class);
    private final com.rtdwh.repository.TaskRunAttemptRepository attempts = mock(com.rtdwh.repository.TaskRunAttemptRepository.class);
    private final WorkflowDependencyService bindings = new WorkflowDependencyService(mock(com.rtdwh.repository.TaskRunDependencyBindingRepository.class), instanceRepository,
            mock(com.rtdwh.repository.DatasetProductionRepository.class), taskRepository, versionRepository, contracts, datasetProductionService);
    @org.junit.jupiter.api.BeforeEach void setupAttempts() {
        when(attempts.saveAndFlush(any())).thenAnswer(call -> { com.rtdwh.entity.TaskRunAttempt a = call.getArgument(0); a.setId(1L); return a; });
        when(contracts.canReadVersion(any(), any())).thenReturn(true);
    }
    private final WorkflowService service = new WorkflowService(taskRepository, dependencyRepository,
            versionRepository, instanceRepository, new ObjectMapper().findAndRegisterModules(), datasetProductionService, contracts, bindings, attempts);

    @Test
    void rejectsDependencyCycle() {
        when(taskRepository.findById(anyLong())).thenAnswer(invocation -> Optional.of(task(invocation.getArgument(0), SyncTask.TaskType.etl)));
        when(dependencyRepository.findAll()).thenReturn(List.of(TaskDependency.builder()
                .upstreamTaskId(2L).downstreamTaskId(1L).build()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.addDependency(1L, 2L, 7L));

        assertEquals("新增依赖会形成环路", exception.getMessage());
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void rejectsVersionPublishingForContinuousJobs() {
        when(taskRepository.findById(5L)).thenReturn(Optional.of(SyncTask.builder().id(5L)
                .taskType(SyncTask.TaskType.cdc_sync).executionMode(SyncTask.ExecutionMode.continuous).build()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.publish(5L, "not applicable", 7L));

        assertEquals("只有周期任务支持发布版本", exception.getMessage());
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createsOneBackfillInstancePerBusinessDate() {
        when(taskRepository.findById(8L)).thenReturn(Optional.of(task(8L, SyncTask.TaskType.etl)));
        when(versionRepository.findById(80L)).thenReturn(Optional.of(TaskDefinitionVersion.builder()
                .id(80L).taskId(8L).versionNo(1).snapshotJson("{}").build()));
        when(dependencyRepository.findByDownstreamTaskId(8L)).thenReturn(List.of());
        when(instanceRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowDTO.BackfillRequest request = new WorkflowDTO.BackfillRequest();
        request.setStartDate(LocalDate.of(2026, 8, 1));
        request.setEndDate(LocalDate.of(2026, 8, 3));
        request.setParametersJson("{\"partition\":\"dt\"}");

        List<TaskRunInstance> instances = service.createBackfill(8L, request, 7L);

        assertEquals(3, instances.size());
        assertTrue(instances.stream().allMatch(item -> item.getStatus() == TaskRunInstance.RunStatus.queued));
        assertTrue(instances.stream().allMatch(item -> item.getDefinitionVersionId().equals(80L)));
        assertEquals(LocalDate.of(2026, 8, 3), instances.get(2).getBusinessDate());
    }

    @Test
    void promotesDownstreamOnlyAfterUpstreamSuccess() {
        TaskRunInstance waiting = TaskRunInstance.builder().id(3L).taskId(20L)
                .businessDate(LocalDate.of(2026, 8, 20)).status(TaskRunInstance.RunStatus.waiting)
                .createdAt(LocalDateTime.now()).build();
        when(instanceRepository.findByStatusOrderByCreatedAtAsc(eq(TaskRunInstance.RunStatus.waiting), any(Pageable.class)))
                .thenReturn(List.of(waiting));
        when(contracts.dependencies(waiting)).thenReturn(List.of(TaskDependency.builder()
                .upstreamTaskId(10L).downstreamTaskId(20L).build()));
        when(instanceRepository.findFirstByTaskIdAndBusinessDateOrderByCreatedAtDesc(
                10L, waiting.getBusinessDate()))
                .thenReturn(Optional.of(TaskRunInstance.builder().id(2L).status(TaskRunInstance.RunStatus.success).build()));

        when(instanceRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(waiting));
        assertEquals(0, service.promoteReadyInstances());
        assertEquals(TaskRunInstance.RunStatus.waiting, waiting.getStatus());
        verify(instanceRepository, never()).save(any());

        when(datasetProductionService.isDeliveryAvailable(any())).thenReturn(true);
        assertEquals(1, service.promoteReadyInstances());
        assertEquals(TaskRunInstance.RunStatus.queued, waiting.getStatus());
        verify(instanceRepository).save(waiting);
    }

    @Test
    void batchOnlyCreatesUpstreamPerDateWhileReuseCreatesOnlyRoot() {
        SyncTask root = task(8L, SyncTask.TaskType.etl);
        SyncTask upstream = task(7L, SyncTask.TaskType.etl); upstream.setPublishedVersionId(70L);
        var rootVersion = TaskDefinitionVersion.builder().id(80L).taskId(8L).build();
        var upstreamVersion = TaskDefinitionVersion.builder().id(70L).taskId(7L).build();
        var dependency = TaskDependency.builder().id(1L).upstreamTaskId(7L).downstreamTaskId(8L).conditionType("execution_success").build();
        when(taskRepository.findById(8L)).thenReturn(Optional.of(root));
        when(taskRepository.findById(7L)).thenReturn(Optional.of(upstream));
        when(versionRepository.findById(80L)).thenReturn(Optional.of(rootVersion));
        when(versionRepository.findById(70L)).thenReturn(Optional.of(upstreamVersion));
        when(contracts.dependencies(rootVersion)).thenReturn(List.of(dependency));
        when(contracts.dependencies(any(TaskRunInstance.class))).thenAnswer(call -> {
            TaskRunInstance run = call.getArgument(0); return run.getTaskId().equals(8L) ? List.of(dependency) : List.of();
        });
        java.util.ArrayList<TaskRunInstance> saved = new java.util.ArrayList<>();
        when(instanceRepository.saveAll(any())).thenAnswer(call -> {
            List<TaskRunInstance> result = call.getArgument(0);
            for (TaskRunInstance run : result) { run.setId((long) saved.size() + 1); saved.add(run); }
            return result;
        });
        when(instanceRepository.findByTaskIdAndBatchIdAndBusinessDate(anyLong(), anyString(), any())).thenAnswer(call -> saved.stream()
                .filter(run -> run.getTaskId().equals(call.getArgument(0)) && run.getBatchId().equals(call.getArgument(1))
                        && run.getBusinessDate().equals(call.getArgument(2))).findFirst());
        WorkflowDTO.BackfillRequest request = new WorkflowDTO.BackfillRequest();
        request.setStartDate(LocalDate.of(2026, 9, 1)); request.setEndDate(LocalDate.of(2026, 9, 2));
        var batch = service.createBackfill(8L, request, 7L);
        assertEquals(4, batch.size());
        assertEquals(1, batch.stream().map(TaskRunInstance::getBatchId).distinct().count());
        assertTrue(batch.stream().allMatch(run -> run.getWindowEnd().equals(run.getWindowStart().plusDays(1))));
        assertTrue(batch.stream().filter(run -> run.getTaskId().equals(8L)).allMatch(run -> run.getStatus() == TaskRunInstance.RunStatus.waiting));
        request.setBindingPolicy("reuse_available");
        var reuse = service.createBackfill(8L, request, 7L);
        assertEquals(2, reuse.size()); assertTrue(reuse.stream().allMatch(run -> run.getTaskId().equals(8L)));
    }

    @Test
    void claimsOnlyInstancesInsideTheCallersDataScope() {
        TaskRunInstance queued = TaskRunInstance.builder().id(31L).taskId(8L)
                .status(TaskRunInstance.RunStatus.queued).createdAt(LocalDateTime.now()).build();
        when(instanceRepository.findRunnableForTaskIdsForUpdate(
                eq(TaskRunInstance.RunStatus.queued), any(LocalDateTime.class), eq(Set.of(8L)), any(Pageable.class)))
                .thenReturn(List.of(queued));
        when(instanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRunInstance claimed = service.claim("scoped-runner", Set.of(8L)).orElseThrow();

        assertEquals(TaskRunInstance.RunStatus.running, claimed.getStatus());
        assertEquals("scoped-runner", claimed.getExecutorId());
        verify(instanceRepository, never()).findRunnableForUpdate(any(), any(), any());
    }

    @Test
    void executesTheDefinitionSnapshotBoundToTheInstance() throws Exception {
        SyncTask published = task(8L, SyncTask.TaskType.etl);
        published.setFlinkSql("INSERT INTO ads.snapshot SELECT * FROM dwd.source");
        when(versionRepository.findById(80L)).thenReturn(Optional.of(TaskDefinitionVersion.builder()
                .id(80L).taskId(8L).versionNo(3)
                .snapshotJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(published)).build()));
        TaskRunInstance instance = TaskRunInstance.builder().taskId(8L).definitionVersionId(80L).build();

        SyncTask definition = service.taskForInstance(instance);

        assertEquals("INSERT INTO ads.snapshot SELECT * FROM dwd.source", definition.getFlinkSql());
        assertEquals(SyncTask.ExecutionMode.scheduled, definition.getExecutionMode());
        verify(taskRepository, never()).findById(anyLong());
    }

    @Test
    void retriesFailedExecutionWithBackoffBeforeTerminalFailure() {
        TaskRunInstance running = TaskRunInstance.builder().id(9L)
                .status(TaskRunInstance.RunStatus.running).retryCount(0).activeAttemptId(1L).executorId("runner").build();
        when(attempts.findById(1L)).thenReturn(Optional.of(com.rtdwh.entity.TaskRunAttempt.builder().id(1L).instanceId(9L).status("claimed").build()));
        when(instanceRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(running));
        when(instanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(service, "maxRetries", 1);
        ReflectionTestUtils.setField(service, "retryBackoffSeconds", 10L);

        TaskRunInstance retrying = service.failOrRetry(9L, "temporary error", 1L, "runner");

        assertEquals(TaskRunInstance.RunStatus.queued, retrying.getStatus());
        assertEquals(1, retrying.getRetryCount());
        assertNotNull(retrying.getNextRetryAt());

        retrying.setStatus(TaskRunInstance.RunStatus.running); retrying.setExecutorId("runner");
        TaskRunInstance failed = service.failOrRetry(9L, "still broken", 1L, "runner");
        assertEquals(TaskRunInstance.RunStatus.failed, failed.getStatus());
        assertNotNull(failed.getFinishedAt());
    }

    @Test
    void computationSuccessQueuesIndependentDeliveryCheck() {
        TaskRunInstance running = TaskRunInstance.builder().id(12L).taskId(8L)
                .businessDate(LocalDate.of(2026, 8, 22)).status(TaskRunInstance.RunStatus.running).build();
        when(instanceRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(running));
        when(instanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRunInstance completed = service.complete(12L, true, null);

        assertEquals(TaskRunInstance.RunStatus.success, completed.getStatus());
        assertEquals("checking", completed.getDeliveryStatus());
        verify(datasetProductionService, never()).recordSuccess(any());
    }

    private SyncTask task(Long id, SyncTask.TaskType type) {
        return SyncTask.builder().id(id).taskName("task-" + id).taskType(type)
                .executionMode(SyncTask.ExecutionMode.scheduled)
                .definitionStatus(SyncTask.DefinitionStatus.published)
                .publishedVersionId(80L)
                .status(SyncTask.TaskStatus.draft).parallelism(1).checkpointIntervalMs(60000L).build();
    }
}
