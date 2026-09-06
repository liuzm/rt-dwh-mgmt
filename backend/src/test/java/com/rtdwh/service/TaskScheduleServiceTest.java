package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskScheduleServiceTest {
    private final TaskScheduleRepository repository = mock(TaskScheduleRepository.class);
    private final SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final TaskScheduleRevisionRepository revisions = mock(TaskScheduleRevisionRepository.class);
    private final TaskScheduleService service = new TaskScheduleService(repository, taskRepository,
            workflowService, new ObjectMapper(), revisions);

    @Test
    void configuresPublishedBatchTaskWithNextRun() {
        when(taskRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(SyncTask.builder().id(8L)
                .taskType(SyncTask.TaskType.etl).executionMode(SyncTask.ExecutionMode.scheduled)
                .definitionStatus(SyncTask.DefinitionStatus.draft).publishedVersionId(80L).build()));
        when(repository.findByTaskId(8L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(revisions.saveAndFlush(any())).thenAnswer(call -> { TaskScheduleRevision revision = call.getArgument(0); revision.setId(90L); return revision; });
        WorkflowDTO.ScheduleRequest request = new WorkflowDTO.ScheduleRequest();
        request.setCronExpression("0 0 2 * * *"); request.setTimezone("Asia/Shanghai");
        request.setBusinessDateOffset(-1); request.setParametersJson("{\"region\":\"east\"}");

        TaskSchedule schedule = service.configure(8L, request, 7L);

        assertTrue(schedule.getEnabled());
        assertNotNull(schedule.getNextRunAt());
        assertEquals("{\"region\":\"east\"}", schedule.getParametersJson());
    }

    @Test
    void createsDueInstanceAndAdvancesSchedule() {
        Instant due = Instant.parse("2026-08-22T00:00:00Z");
        TaskSchedule schedule = TaskSchedule.builder().id(3L).taskId(8L).cronExpression("0 0 * * * *")
                .timezone("Asia/Shanghai").businessDateOffset(-1).parametersJson("{}").enabled(true)
                .nextRunAt(due).createdBy(7L).activeRevisionId(90L).build();
        when(revisions.findById(90L)).thenReturn(Optional.of(TaskScheduleRevision.builder().id(90L).taskId(8L)
                .cronExpression("0 0 * * * *").timezone("Asia/Shanghai").businessDateOffset(-1)
                .parametersJson("{}").createdBy(9L).build()));
        when(repository.findDueForUpdate(any(), any())).thenReturn(List.of(schedule), List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(1, service.runDue());
        assertEquals(due, schedule.getLastRunAt());
        assertTrue(schedule.getNextRunAt().isAfter(due));
        verify(workflowService).createScheduledInstance(eq(8L), eq(3L), eq(due),
                eq(LocalDate.of(2026, 8, 21)), eq("{}"), eq(9L), eq(90L));
    }
    @Test void editingSchedulePreservesPreviousRevisionAndChangesActor() {
        when(taskRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(SyncTask.builder().id(8L)
                .executionMode(SyncTask.ExecutionMode.scheduled).publishedVersionId(80L).build()));
        TaskScheduleRevision old = TaskScheduleRevision.builder().id(90L).revisionNo(1).cronExpression("0 0 2 * * *").createdBy(7L).build();
        when(revisions.findFirstByTaskIdOrderByRevisionNoDesc(8L)).thenReturn(Optional.of(old));
        when(repository.findByTaskId(8L)).thenReturn(Optional.of(TaskSchedule.builder().id(3L).taskId(8L).createdBy(7L).activeRevisionId(90L).build()));
        when(revisions.saveAndFlush(any())).thenAnswer(call -> { TaskScheduleRevision revision = call.getArgument(0); revision.setId(91L); return revision; });
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        WorkflowDTO.ScheduleRequest request = new WorkflowDTO.ScheduleRequest(); request.setCronExpression("0 0 4 * * *");
        TaskSchedule current = service.configure(8L, request, 9L);
        assertEquals(91L, current.getActiveRevisionId());
        assertEquals("0 0 2 * * *", old.getCronExpression());
        verify(revisions).saveAndFlush(argThat(value -> value.getRevisionNo() == 2 && value.getCreatedBy() == 9L));
    }

    @Test void incompatiblePublishedParametersDisableScheduleWithoutSubmitting() {
        TaskSchedule schedule = TaskSchedule.builder().id(3L).taskId(8L).activeRevisionId(90L).enabled(true)
                .nextRunAt(Instant.now().minusSeconds(1)).build();
        when(repository.findDueForUpdate(any(), any())).thenReturn(List.of(schedule), List.of());
        when(revisions.findById(90L)).thenReturn(Optional.of(TaskScheduleRevision.builder().id(90L).taskId(8L).parametersJson("{}").build()));
        doThrow(new IllegalArgumentException("missing required")).when(workflowService).validateScheduleParameters(8L, "{}");
        assertEquals(1, service.runDue()); assertFalse(schedule.getEnabled()); assertNotNull(schedule.getLastError());
        verify(workflowService, never()).createScheduledInstance(any(), any(), any(), any(), any(), any(), any());
    }
}
