package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.MaintenanceRecoveryDTO;
import com.rtdwh.entity.*;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.repository.*;
import com.rtdwh.util.SecurityContextUtil;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaimonMaintenanceServiceTest {
    final TableMaintenanceLogRepository repository = mock(TableMaintenanceLogRepository.class);
    final MaintenanceRecoveryEventRepository events = mock(MaintenanceRecoveryEventRepository.class);
    final MaintenancePersistenceService persistence = mock(MaintenancePersistenceService.class);
    final MaintenanceCoordinationLock locks = mock(MaintenanceCoordinationLock.class);
    final SqlGatewayClient gateway = mock(SqlGatewayClient.class);
    final FlinkClusterService runtime = mock(FlinkClusterService.class);
    final ObjectMapper mapper = new ObjectMapper();
    final DwhTableMetaRepository tables = mock(DwhTableMetaRepository.class);
    final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    final SysUserRepository users = mock(SysUserRepository.class);
    final SecurityContextUtil security = mock(SecurityContextUtil.class);
    final TableMaintenanceLog entry = TableMaintenanceLog.builder().id(1L).tableMetaId(2L)
            .sqlContent("CALL sys.compact('ods.orders')").startedAt(LocalDateTime.now()).build();
    final DwhTableMeta table = DwhTableMeta.builder().id(2L).assetId("asset-2").catalogName("rtdwh_paimon").paimonDb("ods").paimonTable("orders").build();
    final SysUser owner = SysUser.builder().id(7L).status(SysUser.UserStatus.active)
            .roles(Set.of(SysRole.builder().roleCode("ADMIN").build())).build();
    PaimonMaintenanceService service;

    PaimonMaintenanceService subject() {
        var value = new PaimonMaintenanceService(repository,events,persistence,locks,gateway,runtime,mapper,tables,access,users,security);
        for (String field : List.of("jdbcUri", "jdbcUser", "jdbcPassword", "warehouse", "catalog")) ReflectionTestUtils.setField(value,field,"test");
        return value;
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        service = subject();
        when(security.getCurrentUserId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(owner)); when(access.isAdmin(7L)).thenReturn(true);
        when(tables.findById(2L)).thenReturn(Optional.of(table));
        when(runtime.isSqlGatewayEnabled()).thenReturn(true); when(runtime.getSqlGatewayUrl()).thenReturn("http://old-gateway");
        when(runtime.getFlinkRestUrl()).thenReturn("http://old-flink");
        when(locks.withTable(eq(2L), any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(1)).get());
        when(persistence.create(any())).thenAnswer(call -> call.getArgument(0));
        when(persistence.get(1L)).thenReturn(entry);
        when(persistence.update(eq(1L), any(), anyString(), any(), any())).thenAnswer(call -> {
            ((Consumer<TableMaintenanceLog>)call.getArgument(4)).accept(entry); entry.setRevision(entry.getRevision()+1); return entry;
        });
        when(repository.findRecoverableIds(anyList())).thenReturn(List.of(1L));
        when(gateway.open(anyString(), anyMap())).thenReturn("session-1");
        when(gateway.submit(anyString(), anyString(), anyString(), anyMap())).thenReturn("catalog-op","use-op","call-op");
        when(gateway.status(anyString(),anyString(),anyString())).thenReturn(mapper.readTree("{\"status\":\"FINISHED\"}"));
        when(gateway.result(anyString(),anyString(),anyString(),eq(0L))).thenReturn(mapper.readTree("{\"resultType\":\"PAYLOAD\",\"results\":{\"data\":[{\"fields\":[\"Success\"]}]}}"));
        when(gateway.close(anyString(),anyString())).thenReturn("closed");
    }
    void submitCall() { service.start(entry); service.reconcile(); service.reconcile(); assertEquals("CALL",entry.getExecutionPhase()); }
    MaintenanceRecoveryDTO command(String action) {
        var request = new MaintenanceRecoveryDTO(); request.setExpectedRevision(entry.getRevision()); request.setAction(action); request.setReason("已核对原环境证据"); return request;
    }

    @Test void commitsIntentBeforeEachExternalMutationAndWaitsForTerminal() {
        submitCall();
        verify(gateway,never()).close(anyString(),anyString());
        var ordered = inOrder(persistence,gateway);
        ordered.verify(persistence).create(entry); ordered.verify(gateway).open(eq("http://old-gateway"),anyMap());
        ordered.verify(persistence).update(eq(1L),isNull(),eq("session_bound"),isNull(),any());
        ordered.verify(persistence).update(eq(1L),isNull(),eq("submit_intent"),anyString(),any());
        ordered.verify(gateway).submit(eq("http://old-gateway"),eq("session-1"),startsWith("CREATE CATALOG"),anyMap());
        service.reconcile(); assertEquals(Status.success,entry.getStatus()); assertEquals("done",entry.getCleanupStatus());
    }
    @Test void restartUsesPersistedEndpointsEvenWhenNewSubmissionIsDisabled() {
        submitCall();
        when(runtime.getSqlGatewayUrl()).thenReturn("http://new-gateway"); when(runtime.getFlinkRestUrl()).thenReturn("http://new-flink");
        when(runtime.isSqlGatewayEnabled()).thenReturn(false);
        subject().reconcile();
        assertEquals(Status.success,entry.getStatus());
        verify(gateway).result("http://old-gateway","session-1","call-op",0);
        verify(gateway).close("http://old-gateway","session-1");
        verify(gateway,never()).status(eq("http://new-gateway"),anyString(),anyString());
    }
    @Test void lostSubmissionResponseDoesNotRepeatSqlOrCleanup() {
        when(gateway.submit(anyString(),anyString(),anyString(),anyMap())).thenThrow(new IllegalStateException("lost"));
        service.start(entry); subject().reconcile();
        assertEquals(Status.unknown,entry.getStatus()); assertNull(entry.getOperationId());
        verify(gateway,times(1)).submit(anyString(),anyString(),anyString(),anyMap()); verify(gateway,never()).close(anyString(),anyString());
    }
    @Test void lostSessionResponseIsNeverRecreated() {
        when(gateway.open(anyString(),anyMap())).thenThrow(new IllegalStateException("lost"));
        service.start(entry); subject().reconcile();
        assertEquals(Status.unknown,entry.getStatus()); assertNull(entry.getSessionId());
        verify(gateway,times(1)).open(anyString(),anyMap()); verify(gateway,never()).submit(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void unfinishedMaintenancePreventsDuplicateSubmission() {
        when(repository.existsByTableMetaIdAndStatusIn(eq(2L),anyList())).thenReturn(true);
        assertThrows(IllegalStateException.class,()->service.start(entry)); verifyNoInteractions(gateway,persistence);
    }
    @Test void closedOperationIsUnknownRatherThanInventedFailure() throws Exception {
        submitCall(); when(gateway.status(anyString(),anyString(),anyString())).thenReturn(mapper.readTree("{\"status\":\"CLOSED\"}"));
        service.reconcile(); assertEquals(Status.unknown,entry.getStatus()); assertNull(entry.getFinishedAt());
        verify(gateway,never()).close(anyString(),anyString());
    }
    @Test void gatewayErrorProvidesTerminalFailureEvidence() throws Exception {
        submitCall(); when(gateway.status(anyString(),anyString(),anyString())).thenReturn(mapper.readTree("{\"status\":\"ERROR\"}"));
        service.reconcile(); assertEquals(Status.failed,entry.getStatus()); assertEquals("done",entry.getCleanupStatus());
    }
    @Test void timeoutPreservesOriginalHandlesWithoutCancellation() throws Exception {
        submitCall(); entry.setStartedAt(LocalDateTime.now().minusHours(1));
        when(gateway.status(anyString(),anyString(),anyString())).thenReturn(mapper.readTree("{\"status\":\"RUNNING\"}"));
        service.reconcile(); assertEquals(Status.timed_out,entry.getStatus()); assertEquals("call-op",entry.getOperationId());
        verify(gateway,never()).close(anyString(),anyString());
    }
    @Test void cleanupRetriesAfterRestartWithoutChangingSuccessfulExecution() {
        submitCall(); when(gateway.close(anyString(),anyString())).thenThrow(new IllegalStateException("offline"));
        service.reconcile(); assertEquals(Status.success,entry.getStatus()); assertEquals("pending",entry.getCleanupStatus());
        LocalDateTime completed = entry.getFinishedAt();
        service.reconcile(); verify(gateway,times(1)).close(anyString(),anyString());
        entry.setCleanupNextAt(LocalDateTime.now().minusSeconds(1)); doReturn("absent").when(gateway).close(anyString(),anyString());
        subject().reconcile(); assertEquals("done",entry.getCleanupStatus()); assertEquals(2,entry.getCleanupAttempts());
        assertEquals(completed,entry.getFinishedAt()); verify(gateway,times(3)).submit(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void revokedOwnerCannotSubmitTheNextPhaseButAlreadySubmittedCallCanBeObserved() {
        service.start(entry); owner.setStatus(SysUser.UserStatus.disabled);
        service.reconcile(); assertEquals("CATALOG",entry.getExecutionPhase()); assertEquals(Status.unknown,entry.getStatus());
        verify(gateway,times(1)).submit(anyString(),anyString(),anyString(),anyMap());
        owner.setStatus(SysUser.UserStatus.active); service.reconcile(); service.reconcile();
        owner.setStatus(SysUser.UserStatus.disabled); service.reconcile(); assertEquals(Status.success,entry.getStatus());
    }
    @Test void frozenHistoryDoesNotMoveWhenCurrentAssetIsRetargeted() {
        service.start(entry); table.setPaimonTable("retargeted");
        when(access.allowed(8L,"rtdwh_paimon","ods","retargeted")).thenReturn(true);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.detail(1L,8L));
        when(access.allowed(9L,"rtdwh_paimon","ods","orders")).thenReturn(true);
        assertDoesNotThrow(()->service.detail(1L,9L));
        service.reconcile(); assertEquals(Status.unknown,entry.getStatus());
        verify(gateway,times(1)).submit(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void legacyRecordDoesNotAcquireInventedEndpointAndOnlyAllowsNote() {
        entry.setStatus(Status.unknown); entry.setSessionId("old"); entry.setOperationId("old-operation");
        service.reconcile(); verifyNoInteractions(gateway);
        assertThrows(IllegalStateException.class,()->service.recover(1L,command("observe"),7L));
        service.recover(1L,command("note"),7L); assertEquals(Status.unknown,entry.getStatus()); assertNull(entry.getGatewayUrl());
    }
    @Test void staleManualDecisionIsRejected() {
        service.start(entry); var request = command("cancel_preparation"); request.setExpectedRevision(entry.getRevision()-1);
        assertThrows(IllegalStateException.class,()->service.recover(1L,request,7L)); assertEquals(Status.running,entry.getStatus());
    }
    @Test void onlyUnsubmittedLegacyPendingRequestCanBeCancelledWithoutInventingEngineEvidence() {
        entry.setStatus(Status.pending);
        service.recover(1L,command("cancel_pending"),7L);
        assertEquals(Status.failed,entry.getStatus()); assertEquals("not_required",entry.getCleanupStatus());
        verifyNoInteractions(gateway);
        entry.setStatus(Status.unknown);
        assertThrows(IllegalStateException.class,()->service.recover(1L,command("cancel_pending"),7L));
    }
    @Test void preparationCancellationIsPossibleOnlyBeforeBusinessCallIntent() {
        service.start(entry); service.recover(1L,command("cancel_preparation"),7L);
        assertEquals(Status.failed,entry.getStatus()); assertEquals("done",entry.getCleanupStatus());
        entry.setStatus(Status.unknown); entry.setExecutionPhase("CALL"); entry.setOperationId(null);
        assertThrows(IllegalStateException.class,()->service.recover(1L,command("cancel_preparation"),7L));
    }
    @Test void manualJobBindingRequiresExactOriginalCorrelationAndEndpoint() throws Exception {
        submitCall(); entry.setOperationId(null); entry.setStatus(Status.unknown);
        var request = command("attach_job"); request.setJobId("0123456789abcdef0123456789abcdef");
        when(gateway.job(anyString(),anyString())).thenReturn(mapper.readTree("{\"jid\":\""+request.getJobId()+"\",\"name\":\"unrelated\",\"state\":\"FINISHED\"}"));
        assertThrows(IllegalArgumentException.class,()->service.recover(1L,request,7L)); assertNull(entry.getFlinkJobId());
        when(gateway.job(anyString(),anyString())).thenReturn(mapper.readTree("{\"jid\":\""+request.getJobId()+"\",\"name\":\""+entry.getCorrelationName()+"\",\"state\":\"FINISHED\"}"));
        service.recover(1L,request,7L); assertEquals(Status.success,entry.getStatus());
        verify(gateway,atLeastOnce()).job("http://old-flink",request.getJobId());
    }
    @Test void engineJobResultRequiresActualJobCompletionAtBoundEndpoint() throws Exception {
        submitCall(); String jobId="0123456789abcdef0123456789abcdef";
        when(gateway.result(anyString(),anyString(),anyString(),eq(0L))).thenReturn(mapper.readTree("{\"results\":{\"data\":[{\"fields\":[\"JobID="+jobId+"\"]}]}}"));
        service.reconcile(); assertEquals("JOB",entry.getExecutionPhase()); assertNull(entry.getFinishedAt());
        when(runtime.getFlinkRestUrl()).thenReturn("http://new-flink");
        when(gateway.job("http://old-flink",jobId)).thenReturn(mapper.readTree("{\"state\":\"FAILED\"}"));
        subject().reconcile(); assertEquals(Status.failed,entry.getStatus());
    }
}
