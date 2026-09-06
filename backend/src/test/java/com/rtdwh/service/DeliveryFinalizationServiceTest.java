package com.rtdwh.service;

import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.TaskRunInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryFinalizationServiceTest {
    private final TaskRunInstanceRepository instances = mock(TaskRunInstanceRepository.class);
    private final DatasetProductionService productions = mock(DatasetProductionService.class);
    private final DeliveryFinalizationService service = new DeliveryFinalizationService(instances, productions);
    private final TaskRunInstance run = TaskRunInstance.builder().id(1L).status(TaskRunInstance.RunStatus.success).deliveryStatus("checking").build();
    @BeforeEach void setup() { when(instances.findByIdForUpdate(1L)).thenReturn(Optional.of(run)); }

    @Test void blockedDeliveryPreservesComputationAndCanBeRechecked() {
        service.finalizeInstance(1L);
        assertEquals("blocked", run.getDeliveryStatus()); assertEquals(TaskRunInstance.RunStatus.success, run.getStatus());
        service.finalizeInstance(1L); verify(productions, times(1)).recordSuccess(run, true);
        service.recheck(1L); when(productions.isDeliveryAvailable(run)).thenReturn(true);
        service.finalizeInstance(1L); assertEquals("available", run.getDeliveryStatus());
        assertThrows(IllegalStateException.class, () -> service.recheck(1L));
    }

    @Test void transientDeliveryFailureLeavesComputationSuccessfulForRetry() {
        doThrow(new IllegalStateException("temporary database outage")).when(productions).recordSuccess(run, true);
        assertThrows(IllegalStateException.class, () -> service.finalizeInstance(1L));
        service.noteError(1L);
        assertEquals(TaskRunInstance.RunStatus.success, run.getStatus()); assertEquals("checking", run.getDeliveryStatus());
        assertNotNull(run.getDeliveryError());
        doNothing().when(productions).recordSuccess(run, true); when(productions.isDeliveryAvailable(run)).thenReturn(true);
        service.finalizeInstance(1L); assertEquals("available", run.getDeliveryStatus()); assertNull(run.getDeliveryError());
    }

    @Test void failedCalculationCannotRegisterOutput() {
        run.setStatus(TaskRunInstance.RunStatus.failed); service.finalizeInstance(1L); verifyNoInteractions(productions);
    }
}
