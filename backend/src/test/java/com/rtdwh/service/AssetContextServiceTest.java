package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import com.rtdwh.util.SecurityContextUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetContextServiceTest {
    final DwhTableMetaRepository tables = mock(DwhTableMetaRepository.class);
    final TaskDefinitionVersionRepository versions = mock(TaskDefinitionVersionRepository.class);
    final TaskOutputDatasetRepository outputs = mock(TaskOutputDatasetRepository.class);
    final DatasetProductionRepository productions = mock(DatasetProductionRepository.class);
    final SyncTaskService tasks = mock(SyncTaskService.class);
    final TaskReleaseContractService contracts = mock(TaskReleaseContractService.class);
    final ReportService reports = mock(ReportService.class);
    final DataServiceService services = mock(DataServiceService.class);
    final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    final SecurityContextUtil security = mock(SecurityContextUtil.class);
    final ObjectMapper mapper = new ObjectMapper();
    final AssetContextService context = new AssetContextService(tables, versions, outputs, productions, tasks, contracts,
            new SqlAssetReferenceService(), reports, services, mock(ReportParameterRenderer.class), mock(DorisConnectionService.class), access, security, mapper, mock(ManagedViewRepository.class), mock(ManagedViewVersionRepository.class), mock(ViewDependencyService.class));
    final DwhTableMeta asset = DwhTableMeta.builder().id(1L).assetId("asset").paimonDb("ods").paimonTable("events").build();

    @Test void relationshipsUsePublishedSqlAndNeverDraftSql() throws Exception {
        when(security.hasAuthority("task:view")).thenReturn(true);
        var task = SyncTask.builder().id(2L).taskName("published consumer").publishedVersionId(3L).flinkSql("SELECT * FROM ods.unpublished").build();
        var frozen = SyncTask.builder().flinkSql("INSERT INTO dwd.result SELECT * FROM ods.events").build();
        var version = TaskDefinitionVersion.builder().id(3L).taskId(2L).versionNo(1).snapshotJson(mapper.writeValueAsString(frozen)).build();
        when(tasks.listTasksForUser(7L,null,null,null)).thenReturn(List.of(task)); when(versions.findById(3L)).thenReturn(Optional.of(version));
        when(contracts.canReadVersion(version,7L)).thenReturn(true);
        var result = context.context(asset,7L);
        assertEquals(1,result.usages().size()); assertEquals("consumer",result.usages().get(0).relation());
        assertEquals("published_sql_ast",result.usages().get(0).evidence()); assertEquals(3L,result.usages().get(0).versionId());
        assertTrue(result.relatedAssets().isEmpty()); // Unreadable related asset is not disclosed.
        when(contracts.canReadVersion(version,7L)).thenReturn(false);
        assertTrue(context.context(asset,7L).usages().isEmpty());
    }
    @Test void declaredOutputRemainsEvidenceWhenSqlIsUnsupported() throws Exception {
        when(security.hasAuthority("task:view")).thenReturn(true);
        var task = SyncTask.builder().id(2L).taskName("producer").publishedVersionId(3L).build();
        var frozen = SyncTask.builder().flinkSql("CREATE TEMPORARY TABLE x (id INT) WITH ('connector'='blackhole')").build();
        var version = TaskDefinitionVersion.builder().id(3L).taskId(2L).versionNo(1).snapshotJson(mapper.writeValueAsString(frozen)).build();
        when(tasks.listTasksForUser(7L,null,null,null)).thenReturn(List.of(task)); when(versions.findById(3L)).thenReturn(Optional.of(version));
        when(contracts.canReadVersion(version,7L)).thenReturn(true);
        var output = TaskOutputDataset.builder().catalogName("rtdwh_paimon").databaseName("ods").tableName("events").build();
        when(contracts.forVersion(version)).thenReturn(new TaskReleaseContractService.Contract(1,List.of(),List.of(new TaskReleaseContractService.Output(output,List.of()))));
        var result = context.context(asset,7L); assertEquals(1,result.usages().size());
        assertEquals("producer",result.usages().get(0).relation()); assertEquals("published_output",result.usages().get(0).evidence());
    }
    @Test void missingModulePermissionDoesNotExposeRelationshipsOrProductions() {
        var result = context.context(asset,7L);
        assertTrue(result.usages().isEmpty()); assertTrue(result.productions().isEmpty());
        verifyNoInteractions(tasks,reports,services,productions);
    }
}
