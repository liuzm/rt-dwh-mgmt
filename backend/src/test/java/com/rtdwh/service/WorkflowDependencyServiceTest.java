package com.rtdwh.service;

import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowDependencyServiceTest {
    private final TaskRunDependencyBindingRepository bindings = mock(TaskRunDependencyBindingRepository.class);
    private final TaskRunInstanceRepository instances = mock(TaskRunInstanceRepository.class);
    private final DatasetProductionRepository productions = mock(DatasetProductionRepository.class);
    private final TaskReleaseContractService contracts = mock(TaskReleaseContractService.class);
    private final TaskDefinitionVersionRepository versions = mock(TaskDefinitionVersionRepository.class);
    private final DatasetProductionService delivery = mock(DatasetProductionService.class);
    private final WorkflowDependencyService service = new WorkflowDependencyService(bindings, instances, productions,
            mock(SyncTaskRepository.class), versions, contracts, delivery);
    private final LocalDate date = LocalDate.of(2026, 9, 5);
    private final TaskRunInstance run = TaskRunInstance.builder().id(2L).createdBy(7L).bindingPolicy("batch_only").build();
    private final TaskRunInstance upstream = TaskRunInstance.builder().id(1L).taskId(10L).definitionVersionId(100L)
            .windowStart(date).windowEnd(date.plusDays(1)).status(TaskRunInstance.RunStatus.success).deliveryStatus("available").build();
    private final TaskRunDependencyBinding binding = TaskRunDependencyBinding.builder().id(5L).instanceId(2L).upstreamTaskId(10L)
            .upstreamVersionId(100L).upstreamInstanceId(1L).outputDatasetId(3L).conditionType("data_available")
            .bindingPolicy("batch_only").windowStart(date).windowEnd(date.plusDays(1)).build();
    private final DatasetProduction production = DatasetProduction.builder().id(9L).instanceId(1L).outputDatasetId(3L)
            .definitionVersionId(100L).windowStart(date).windowEnd(date.plusDays(1)).status("available").build();

    @BeforeEach void setup() {
        when(bindings.findByInstanceIdOrderById(2L)).thenReturn(List.of(binding));
        when(contracts.dependencies(run)).thenReturn(List.of(TaskDependency.builder().id(5L).build()));
        when(instances.findById(1L)).thenReturn(Optional.of(upstream));
        when(productions.findByInstanceId(1L)).thenReturn(List.of(production));
        when(productions.findById(9L)).thenReturn(Optional.of(production));
    }

    @Test void batchDependencyBindsExactProductionAndDoesNotFallbackToHistoricalSuccess() {
        upstream.setStatus(TaskRunInstance.RunStatus.queued);
        assertFalse(service.ready(run));
        verify(productions, never()).findFirstByOutputDatasetIdAndDefinitionVersionIdAndWindowStartAndWindowEndAndStatusOrderByIdDesc(any(), any(), any(), any(), any());
        upstream.setStatus(TaskRunInstance.RunStatus.success);
        assertTrue(service.ready(run)); assertEquals(9L, binding.getProductionId()); assertNotNull(binding.getBoundAt());
    }

    @Test void selectedOutputMustBeAvailableAndSameVersionAndWindow() {
        production.setStatus("blocked"); assertFalse(service.ready(run));
        production.setStatus("available"); production.setDefinitionVersionId(99L); assertFalse(service.ready(run));
        production.setDefinitionVersionId(100L); production.setWindowEnd(date.plusDays(2)); assertFalse(service.ready(run));
        production.setWindowEnd(date.plusDays(1)); upstream.setDeliveryStatus("checking"); assertFalse(service.ready(run));
        upstream.setDeliveryStatus("blocked"); // A separate output can be blocked; selected output is what this edge requires.
        assertTrue(service.ready(run));
        production.setStatus("blocked"); assertFalse(service.ready(run)); // Revalidate even after it was bound.
    }

    @Test void explicitControlDependencyIgnoresDeliveryFailure() {
        binding.setConditionType("execution_success"); upstream.setDeliveryStatus("blocked"); production.setStatus("blocked");
        assertTrue(service.ready(run)); assertNull(binding.getProductionId()); verifyNoInteractions(productions);
    }

    @Test void reuseRequiresPublishedVersionAndPersistsChosenInstance() {
        run.setBindingPolicy("reuse_available"); binding.setBindingPolicy("reuse_available"); binding.setUpstreamInstanceId(null);
        when(productions.findFirstByOutputDatasetIdAndDefinitionVersionIdAndWindowStartAndWindowEndAndStatusOrderByIdDesc(
                3L, 100L, date, date.plusDays(1), "available")).thenReturn(Optional.of(production));
        assertTrue(service.ready(run)); assertEquals(1L, binding.getUpstreamInstanceId()); assertEquals(9L, binding.getProductionId());
        service.ready(run);
        verify(productions, times(1)).findFirstByOutputDatasetIdAndDefinitionVersionIdAndWindowStartAndWindowEndAndStatusOrderByIdDesc(
                3L, 100L, date, date.plusDays(1), "available");
    }

    @Test void missingBindingFailsClosed() {
        when(bindings.findByInstanceIdOrderById(2L)).thenReturn(List.of()); assertFalse(service.ready(run));
    }

    @Test void upstreamPermissionIsRecheckedBeforeSubmission() {
        var version = TaskDefinitionVersion.builder().id(100L).taskId(10L).build();
        when(versions.findById(100L)).thenReturn(Optional.of(version));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.assertAccess(run));
        when(contracts.canReadVersion(version, 7L)).thenReturn(true); assertDoesNotThrow(() -> service.assertAccess(run));
    }
}
