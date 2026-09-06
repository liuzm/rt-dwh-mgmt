package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowAttemptTest {
    private final TaskRunInstanceRepository instances = mock(TaskRunInstanceRepository.class);
    private final TaskRunAttemptRepository attempts = mock(TaskRunAttemptRepository.class);
    private final WorkflowDependencyService dependencies = mock(WorkflowDependencyService.class);
    private final DatasetProductionService productions = mock(DatasetProductionService.class);
    private final WorkflowService service = new WorkflowService(mock(SyncTaskRepository.class), mock(TaskDependencyRepository.class),
            mock(TaskDefinitionVersionRepository.class), instances, new ObjectMapper(), productions,
            mock(TaskReleaseContractService.class), dependencies, attempts);
    private final TaskRunInstance run = TaskRunInstance.builder().id(10L).taskId(1L).activeAttemptId(11L)
            .executorId("worker").status(TaskRunInstance.RunStatus.running).build();
    private final TaskRunAttempt attempt = TaskRunAttempt.builder().id(11L).instanceId(10L).executorId("worker").status("claimed").build();

    @BeforeEach void setup() {
        when(instances.findByIdForUpdate(10L)).thenReturn(Optional.of(run));
        when(instances.save(any())).thenAnswer(call -> call.getArgument(0));
        when(attempts.findById(11L)).thenReturn(Optional.of(attempt));
        when(dependencies.ready(run)).thenReturn(true);
    }

    @Test void staleAttemptAndDifferentExecutorCannotMutateRun() {
        assertThrows(IllegalStateException.class, () -> service.complete(10L, false, "stale", 9L, "worker"));
        assertThrows(IllegalStateException.class, () -> service.heartbeat(10L, "worker", 9L));
        assertThrows(IllegalStateException.class, () -> service.attachExternalJob(10L, "other", "job", 11L));
        assertThrows(IllegalStateException.class, () -> service.beginSubmission(10L, 9L, "worker"));
        assertThrows(IllegalStateException.class, () -> service.complete(10L, false, "unfenced"));
        assertEquals(TaskRunInstance.RunStatus.running, run.getStatus());
        verify(instances, never()).save(any());
    }

    @Test void completionIsIdempotentAndCannotReopenOrReverseTerminalState() {
        run.setExternalJobId("job-1");
        service.complete(10L, true, null, 11L, "worker");
        var finished = run.getFinishedAt();
        service.complete(10L, true, null, 11L, "worker");
        assertEquals(finished, run.getFinishedAt());
        assertEquals("checking", run.getDeliveryStatus());
        assertThrows(IllegalStateException.class, () -> service.complete(10L, false, "late", 11L, "worker"));
        verify(instances, times(1)).save(run);
        verifyNoInteractions(productions);
    }

    @Test void bindingJobRequiresDurableIntentAndIntentCannotBeRepeated() {
        assertThrows(IllegalStateException.class, () -> service.attachExternalJob(10L, "worker", "job-1", 11L));
        assertNull(run.getExternalJobId());
        service.beginSubmission(10L, 11L, "worker");
        assertNotNull(attempt.getSubmittedAt());
        assertThrows(IllegalStateException.class, () -> service.beginSubmission(10L, 11L, "worker"));
        service.attachExternalJob(10L, "worker", "job-1", 11L);
        assertEquals("job-1", attempt.getExternalJobId());
        assertThrows(IllegalStateException.class, () -> service.attachExternalJob(10L, "worker", "job-2", 11L));
    }

    @Test void dependencyIsRevalidatedBeforeSubmission() {
        when(dependencies.ready(run)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.beginSubmission(10L, 11L, "worker"));
        assertNull(attempt.getSubmittedAt());
    }

    @Test void submittedFailureRetainsJobAndCannotAutomaticallyOrManuallyReplay() {
        attempt.setSubmittedAt(LocalDateTime.now()); attempt.setExternalJobId("job-1"); run.setExternalJobId("job-1");
        service.failOrRetry(10L, "engine failed", 11L, "worker");
        assertEquals(TaskRunInstance.RunStatus.failed, run.getStatus());
        assertEquals("job-1", run.getExternalJobId());
        assertEquals("job-1", attempt.getExternalJobId());
        assertNull(run.getNextRetryAt());
        assertThrows(IllegalStateException.class, () -> service.retryFailed(10L));
    }

    @Test void unknownSubmissionCannotBeCancelledOrReplayed() {
        service.beginSubmission(10L, 11L, "worker");
        service.submissionUnknown(10L, 11L, "worker");
        assertEquals("unknown", attempt.getStatus());
        assertEquals(TaskRunInstance.RunStatus.running, run.getStatus());
        assertThrows(IllegalStateException.class, () -> service.cancel(10L));
        assertThrows(IllegalStateException.class, () -> service.retryFailed(10L));
        assertNull(run.getNextRetryAt());
    }

    @Test void manualPreSubmissionRetryIsImmediatelyEligibleAndRetainsFailedAttempt() {
        service.complete(10L, false, "validation failure", 11L, "worker");
        service.retryFailed(10L);
        assertEquals(TaskRunInstance.RunStatus.queued, run.getStatus());
        assertNull(run.getNextRetryAt()); assertNull(run.getExecutorId());
        assertEquals("failed", attempt.getStatus()); assertNotNull(attempt.getFinishedAt());
    }

    @Test void leaseRecoveryRechecksFreshHeartbeatUnderLock() {
        TaskRunInstance stale = TaskRunInstance.builder().id(10L).leaseExpiresAt(LocalDateTime.now().minusMinutes(2)).build();
        when(instances.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(any(), any(), any())).thenReturn(List.of(stale));
        run.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        assertEquals(0, service.recoverExpiredInstances());
        assertEquals(TaskRunInstance.RunStatus.running, run.getStatus());
        verify(instances, never()).save(any());
    }

    @Test void expiredSubmissionRetainsAttemptInsteadOfCreatingAnotherWrite() {
        when(instances.findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(any(), any(), any())).thenReturn(List.of(run));
        run.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1)); attempt.setSubmittedAt(LocalDateTime.now().minusMinutes(2));
        assertEquals(1, service.recoverExpiredInstances());
        assertEquals("unknown", attempt.getStatus());
        assertEquals(11L, run.getActiveAttemptId());
        assertEquals(TaskRunInstance.RunStatus.running, run.getStatus());
        verify(attempts, never()).saveAndFlush(any());
    }
}
