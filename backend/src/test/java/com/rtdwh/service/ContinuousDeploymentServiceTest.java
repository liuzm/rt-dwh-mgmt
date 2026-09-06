package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContinuousDeploymentServiceTest {
    private final TaskDefinitionVersionRepository versions = mock(TaskDefinitionVersionRepository.class);
    private final TaskDeploymentRevisionRepository deployments = mock(TaskDeploymentRevisionRepository.class);
    private final SyncTaskRepository tasks = mock(SyncTaskRepository.class);
    private final TaskReleaseContractService contracts = mock(TaskReleaseContractService.class);
    private final CdcSqlGenerator generator = mock(CdcSqlGenerator.class);
    private final DatasourceService sources = mock(DatasourceService.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final FlinkClusterService flink = mock(FlinkClusterService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ContinuousDeploymentService service = new ContinuousDeploymentService(versions, deployments, tasks,
            contracts, generator, sources, access, flink, mapper, mock(TaskAccessAuditService.class));

    @Test void startUsesPublishedDefinitionEvenAfterDraftChanges() throws Exception {
        SyncTask draft = task(); draft.setFlinkSql("select 999"); draft.setParallelism(8);
        when(versions.findById(10L)).thenReturn(Optional.of(version(10L, "select 1", 2)));
        when(access.isAdmin(7L)).thenReturn(true);
        var prepared = service.prepare(draft, 7L, false);
        assertEquals("select 1", prepared.executable().getFlinkSql());
        assertEquals(2, prepared.executable().getParallelism());
        assertEquals("select 999", draft.getFlinkSql());
    }

    @Test void resumeKeepsOriginalDeploymentVersionWhenNewVersionIsPublished() throws Exception {
        SyncTask task = task(); task.setPublishedVersionId(20L); task.setActiveDeploymentId(3L);
        when(deployments.findById(3L)).thenReturn(Optional.of(TaskDeploymentRevision.builder().taskId(1L).definitionVersionId(10L).build()));
        when(versions.findById(10L)).thenReturn(Optional.of(version(10L, "select 1", 2)));
        when(access.isAdmin(7L)).thenReturn(true);
        assertEquals(10L, service.prepare(task, 7L, true).version().getId());
        verify(versions, never()).findById(20L);
    }

    @Test void uncertainSubmissionBlocksDuplicateStart() {
        when(deployments.existsByTaskIdAndStatusInAndFlinkJobIdIsNull(eq(1L), anyList())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.prepare(task(), 7L, false));
        verifyNoInteractions(flink, generator, versions);
    }

    @Test void revokedPublishedSqlCannotBeExecuted() throws Exception {
        when(versions.findById(10L)).thenReturn(Optional.of(version(10L, "select * from private_table", 1)));
        doThrow(new IllegalArgumentException("revoked")).when(access).validate(7L, "select * from private_table", "rtdwh_paimon", "ods");
        assertThrows(IllegalArgumentException.class, () -> service.prepare(task(), 7L, false));
        verifyNoInteractions(flink);
    }

    @Test void publishStoresCdcTemplateAndDoesNotMutateDraftSql() {
        SyncTask task = task(); task.setTaskType(SyncTask.TaskType.cdc_sync); task.setSourceConfigId(8L); task.setFlinkSql(null);
        when(access.isAdmin(7L)).thenReturn(true);
        when(generator.generateReleaseSql(eq(task), any())).thenReturn("'password'='__RTDWH_SOURCE_CREDENTIAL__'");
        when(contracts.snapshot(1L)).thenReturn("{}");
        when(versions.saveAndFlush(any())).thenAnswer(call -> { TaskDefinitionVersion v = call.getArgument(0); v.setId(11L); return v; });
        TaskDefinitionVersion version = service.publish(task, 7L, "publish");
        assertTrue(version.getSnapshotJson().contains("__RTDWH_SOURCE_CREDENTIAL__"));
        assertNotNull(version.getContractHash());
        assertNull(task.getFlinkSql());
        assertEquals(11L, task.getPublishedVersionId());
    }

    @Test void revokedOutputBlocksPublishedExecution() throws Exception {
        TaskDefinitionVersion version = version(10L, "select 1", 1);
        when(versions.findById(10L)).thenReturn(Optional.of(version));
        TaskOutputDataset output = TaskOutputDataset.builder().catalogName("c").databaseName("d").tableName("restricted").build();
        when(contracts.forVersion(version)).thenReturn(new TaskReleaseContractService.Contract(1, List.of(),
                List.of(new TaskReleaseContractService.Output(output, List.of()))));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(task(), 7L, false));
        verifyNoInteractions(generator, flink);
    }

    private SyncTask task() {
        return SyncTask.builder().id(1L).creatorId(7L).taskType(SyncTask.TaskType.etl)
                .executionMode(SyncTask.ExecutionMode.continuous).publishedVersionId(10L).build();
    }
    private TaskDefinitionVersion version(Long id, String sql, int parallelism) throws Exception {
        SyncTask snapshot = task(); snapshot.setFlinkSql(sql); snapshot.setParallelism(parallelism);
        return TaskDefinitionVersion.builder().id(id).taskId(1L).snapshotJson(mapper.writeValueAsString(snapshot)).build();
    }
}
