package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenancePersistenceServiceTest {
    final TableMaintenanceLogRepository logs = mock(TableMaintenanceLogRepository.class);
    final MaintenanceRecoveryEventRepository events = mock(MaintenanceRecoveryEventRepository.class);
    final MaintenanceCoordinationLock locks = mock(MaintenanceCoordinationLock.class);
    final MaintenancePersistenceService service = new MaintenancePersistenceService(logs,events,new ObjectMapper(),locks);
    final TableMaintenanceLog entry = TableMaintenanceLog.builder().id(1L).status(TableMaintenanceLog.Status.unknown)
            .executionPhase("CALL").coordinationToken("new-owner").sqlContent("must not leak credentials").build();

    @Test void lostGuardOwnerCannotOverwriteNewOwnersPhaseOrHandles() {
        when(logs.findByIdForUpdate(1L)).thenReturn(Optional.of(entry)); when(locks.token()).thenReturn("stale-owner");
        assertThrows(IllegalStateException.class,()->service.update(1L,null,"operation_bound",null,value -> value.setOperationId("stale-handle")));
        assertNull(entry.getOperationId()); verify(logs,never()).saveAndFlush(any()); verifyNoInteractions(events);
    }
    @Test void newClaimFencesOldWorkerWithoutInvalidatingManualRevision() {
        when(logs.findByIdForUpdate(1L)).thenReturn(Optional.of(entry)); when(locks.token()).thenReturn("next-owner");
        service.claim(1L); assertEquals("next-owner",entry.getCoordinationToken()); assertEquals(0,entry.getRevision());
        verify(logs).saveAndFlush(entry);
    }
    @Test void stableObservationsDoNotInvalidateManualInputAndEventsExcludeSecrets() {
        when(logs.findByIdForUpdate(1L)).thenReturn(Optional.of(entry)); when(locks.token()).thenReturn("new-owner");
        service.update(1L,null,"engine_observed",null,value -> value.setObservedAt(LocalDateTime.now()));
        assertEquals(0,entry.getRevision()); verifyNoInteractions(events);
        service.update(1L,null,"operation_bound",null,value -> value.setOperationId("op-1"));
        assertEquals(1,entry.getRevision());
        var recorded = ArgumentCaptor.forClass(MaintenanceRecoveryEvent.class); verify(events).save(recorded.capture());
        assertTrue(recorded.getValue().getEvidenceJson().contains("op-1"));
        assertFalse(recorded.getValue().getEvidenceJson().contains("credentials"));
        assertFalse(recorded.getValue().getEvidenceJson().contains("new-owner"));
    }
}
