package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskReleaseContractServiceTest {
    private final TaskDependencyRepository dependencies = mock(TaskDependencyRepository.class);
    private final TaskOutputDatasetRepository outputs = mock(TaskOutputDatasetRepository.class);
    private final TaskDefinitionVersionRepository versions = mock(TaskDefinitionVersionRepository.class);
    private final QualityCheckService quality = mock(QualityCheckService.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final TaskReleaseContractService service = new TaskReleaseContractService(dependencies, outputs, versions,
            quality, new ObjectMapper().findAndRegisterModules(), access, new TaskParameterService(new ObjectMapper()),
            mock(RuntimeEnvironmentService.class), mock(TaskAccessAuditService.class));

    @Test void freezesOutputsDependenciesAndRuleVersionsTogether() {
        TaskOutputDataset output = TaskOutputDataset.builder().id(8L).taskId(1L).catalogName("lake")
                .databaseName("ads").tableName("orders").qualityGateEnabled(true).slaMinutes(60).build();
        TaskDependency dependency = TaskDependency.builder().upstreamTaskId(2L).downstreamTaskId(1L).build();
        QualityRule rule = QualityRule.builder().id(3L).version(4L).threshold(0.0).targetTable("lake.ads.orders").build();
        when(outputs.findByTaskIdAndEnabledTrueOrderById(1L)).thenReturn(List.of(output));
        when(dependencies.findByDownstreamTaskId(1L)).thenReturn(List.of(dependency));
        when(quality.snapshotRulesForTable("lake", "ads", "orders")).thenReturn(List.of(rule));
        String json = service.snapshot(1L);
        TaskDefinitionVersion version = version(json);
        output.setQualityGateEnabled(false);
        output.setSlaMinutes(1440);
        rule.setThreshold(100.0);
        rule.setVersion(5L);
        dependency.setUpstreamTaskId(99L);
        var frozen = service.forVersion(version);
        assertTrue(frozen.outputs().get(0).definition().getQualityGateEnabled());
        assertEquals(60, frozen.outputs().get(0).definition().getSlaMinutes());
        assertEquals(0.0, frozen.outputs().get(0).rules().get(0).getThreshold());
        assertEquals(4L, frozen.outputs().get(0).rules().get(0).getVersion());
        assertEquals(2L, frozen.dependencies().get(0).getUpstreamTaskId());
    }

    @Test void corruptedContractNeverFallsBackToDraft() {
        TaskDefinitionVersion version = version("{\"format\":1,\"dependencies\":[],\"outputs\":[]}");
        version.setContractJson("{}");
        assertThrows(IllegalStateException.class, () -> service.forVersion(version));
        verifyNoInteractions(outputs, dependencies);
    }

    @Test void versionVisibilityUsesPublishedSqlInsteadOfCurrentDraft() {
        TaskDefinitionVersion version = TaskDefinitionVersion.builder().taskId(1L)
                .snapshotJson("{\"flinkSql\":\"select * from private_table\"}").build();
        assertFalse(service.canReadVersion(version, 7L));
        verify(access).canAccessSql(7L, "select * from private_table", "rtdwh_paimon", "ods");
        when(access.canAccessSql(7L, "select * from private_table", "rtdwh_paimon", "ods")).thenReturn(true);
        assertTrue(service.canReadVersion(version, 7L));
    }

    @Test void historicalVersionsAreExplicitlyUnverified() {
        TaskDefinitionVersion legacy = TaskDefinitionVersion.builder().taskId(1L).snapshotJson("{}").build();
        assertNull(service.forVersion(legacy));
        assertEquals("legacy-inferred", legacy.getContractProvenance());
    }

    @Test void executionRechecksCurrentPermissionsAgainstFrozenOutput() {
        TaskDefinitionVersion version = version("{\"format\":1,\"dependencies\":[],\"outputs\":[{\"definition\":{\"catalogName\":\"lake\",\"databaseName\":\"ads\",\"tableName\":\"orders\"},\"rules\":[]}]}");
        when(versions.findById(10L)).thenReturn(Optional.of(version));
        TaskRunInstance instance = TaskRunInstance.builder().taskId(1L).definitionVersionId(10L).createdBy(7L).build();
        SyncTask task = SyncTask.builder().flinkSql("select 1").build();
        assertThrows(IllegalArgumentException.class, () -> service.validateExecution(instance, task));
        verify(access).validate(7L, "select 1", "rtdwh_paimon", "ods");
        when(access.allowed(7L, "lake", "ads", "orders")).thenReturn(true);
        assertDoesNotThrow(() -> service.validateExecution(instance, task));
    }

    private TaskDefinitionVersion version(String contract) {
        return TaskDefinitionVersion.builder().id(10L).taskId(1L).snapshotJson("{}")
                .contractJson(contract).contractHash(TaskReleaseContractService.fingerprint("{}", contract)).build();
    }
}
