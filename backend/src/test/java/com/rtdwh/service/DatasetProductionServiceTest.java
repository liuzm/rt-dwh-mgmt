package com.rtdwh.service;

import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.DatasetProduction;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.entity.TaskOutputDataset;
import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.repository.DatasetProductionRepository;
import com.rtdwh.repository.DwhTableMetaRepository;
import com.rtdwh.repository.TaskOutputDatasetRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasetProductionServiceTest {
    private final TaskOutputDatasetRepository outputs = mock(TaskOutputDatasetRepository.class);
    private final DatasetProductionRepository productions = mock(DatasetProductionRepository.class);
    private final DwhTableMetaRepository tables = mock(DwhTableMetaRepository.class);
    private final QualityCheckService quality = mock(QualityCheckService.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final TaskReleaseContractService contracts = mock(TaskReleaseContractService.class);
    @org.junit.jupiter.api.BeforeEach void assignProductionIds() {
        when(tables.saveAndFlush(any())).thenAnswer(call -> { DwhTableMeta t = call.getArgument(0); t.setAssetId("test-asset"); return t; });
        when(productions.saveAndFlush(any())).thenAnswer(call -> { DatasetProduction p = call.getArgument(0); p.setId(100L); return p; });
    }
    private final com.rtdwh.repository.DatasetProductionCheckRepository checks = mock(com.rtdwh.repository.DatasetProductionCheckRepository.class);
    private final DatasetProductionService service = new DatasetProductionService(outputs, productions, tables, quality, access, contracts, checks);

    @Test
    void repeatedRegistrationSkipsExistingDeliveryAndRecheckAppendsEvidence() {
        TaskRunInstance run = instance();
        TaskOutputDataset output = output(9L, true);
        var rule = com.rtdwh.entity.QualityRule.builder().id(2L).version(1L).build();
        when(contracts.forInstance(run)).thenReturn(new TaskReleaseContractService.Contract(1, List.of(),
                List.of(new TaskReleaseContractService.Output(output, List.of(rule)))));
        DatasetProduction existing = DatasetProduction.builder().id(100L).instanceId(21L).outputDatasetId(9L).status("blocked").build();
        when(productions.findByDeliveryKey("21:9")).thenReturn(Optional.of(existing));
        service.recordSuccess(run);
        verifyNoInteractions(quality, checks);
        when(quality.runFrozenProductionRules(List.of(rule), run.getWindowStart(), run.getWindowEnd())).thenReturn(summary(1, 1, 0, 0));
        service.recordSuccess(run, true);
        assertEquals("available", existing.getStatus());
        verify(checks).save(argThat(check -> check.getProductionId().equals(100L) && "available".equals(check.getStatus())));
        verify(productions, never()).saveAndFlush(any());
        verify(quality, never()).runChecksForTableWithSummary(any(), any(), any());
    }

    @Test
    void recheckingOlderOutputDoesNotRenewFreshnessOrReplaceLatestInstance() {
        TaskRunInstance run = instance();
        TaskOutputDataset output = output(9L, false);
        LocalDateTime newer = LocalDateTime.of(2026, 9, 5, 0, 0);
        output.setLastProducedAt(newer); output.setLastInstanceId(99L);
        when(outputs.findById(9L)).thenReturn(Optional.of(output));
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(productions.findByDeliveryKey("21:9")).thenReturn(Optional.of(DatasetProduction.builder().id(100L)
                .producedAt(newer.minusDays(1)).build()));
        service.recordSuccess(run, true);
        assertEquals(newer, output.getLastProducedAt()); assertEquals(99L, output.getLastInstanceId());
        verify(outputs, never()).save(any());
    }

    @Test
    void dependencyRequiresAllDeclaredOutputsAndRetainsHistoricalBlocks() {
        TaskRunInstance instance = instance();
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output(9L, true)));
        assertFalse(service.isDeliveryAvailable(instance));
        DatasetProduction production = DatasetProduction.builder().outputDatasetId(9L).status("blocked").build();
        when(productions.findByInstanceId(instance.getId())).thenReturn(List.of(production));
        assertFalse(service.isDeliveryAvailable(instance));
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of());
        assertFalse(service.isDeliveryAvailable(instance));
        production.setStatus("available");
        assertTrue(service.isDeliveryAvailable(instance));
    }

    @Test
    void frozenGateStillBlocksWhenDraftOutputDisablesQuality() {
        TaskRunInstance run = instance();
        TaskOutputDataset frozen = output(9L, true);
        var rule = com.rtdwh.entity.QualityRule.builder().id(2L).version(1L).build();
        when(contracts.forInstance(run)).thenReturn(new TaskReleaseContractService.Contract(1, List.of(),
                List.of(new TaskReleaseContractService.Output(frozen, List.of(rule)))));
        when(quality.runFrozenProductionRules(List.of(rule), run.getWindowStart(), run.getWindowEnd())).thenReturn(summary(1, 0, 0, 1));
        service.recordSuccess(run);
        verify(productions).save(argThat(value -> "blocked".equals(value.getStatus()) && value.getInstanceId().equals(run.getId())));
        verify(quality, never()).runChecksForTableWithSummary(anyString(), anyString(), anyString());
        verify(outputs, never()).findByTaskIdAndEnabledTrueOrderById(anyLong());
        verify(outputs, never()).save(any());
    }

    @Test
    void preservesProductionHistoryWhenOutputConfigurationIsRemovedAndRestored() {
        TaskOutputDataset existing = output(9L, true);
        existing.setLastProducedAt(LocalDateTime.parse("2026-08-21T02:00:00"));
        when(outputs.findByTaskIdOrderById(3L)).thenReturn(List.of(existing));
        when(outputs.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(service.replaceOutputs(3L, List.of()).isEmpty());
        assertFalse(existing.getEnabled());
        verify(outputs, never()).deleteByTaskId(anyLong());

        WorkflowDTO.OutputDatasetRequest request = request();
        List<TaskOutputDataset> restored = service.replaceOutputs(3L, List.of(request));

        assertEquals(List.of(existing), restored);
        assertTrue(existing.getEnabled());
        assertEquals(LocalDateTime.parse("2026-08-21T02:00:00"), existing.getLastProducedAt());
    }

    @Test
    void blocksProducedDatasetWhenQualityGateFails() {
        TaskOutputDataset output = output(9L, true);
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(quality.runChecksForTableWithSummary("rtdwh_paimon", "ads", "daily_sales"))
                .thenReturn(summary(1, 0, 1, 0));

        service.recordSuccess(instance());

        verify(productions).save(argThat(value -> "blocked".equals(value.getStatus())));
        verify(outputs, never()).save(any());
        verify(tables, never()).save(any());
        assertNull(output.getLastProducedAt());
    }

    @Test
    void registersAvailableDatasetAsDataAsset() {
        TaskOutputDataset output = output(9L, true);
        when(outputs.findById(9L)).thenReturn(Optional.of(output));
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(quality.runChecksForTableWithSummary("rtdwh_paimon", "ads", "daily_sales"))
                .thenReturn(summary(1, 1, 0, 0));
        when(tables.findByPaimonDbAndPaimonTable("ads", "daily_sales")).thenReturn(Optional.empty());

        service.recordSuccess(instance());

        verify(productions).save(argThat(value -> "available".equals(value.getStatus())));
        verify(tables).save(argThat(table -> table.getLayer() == DwhTableMeta.TableLayer.ads
                && "daily_sales".equals(table.getPaimonTable())));
        assertNotNull(output.getLastProducedAt());
        assertEquals(21L, output.getLastInstanceId());
    }

    @Test
    void blocksQualityGateWhenNoRuleMatchesTheDataset() {
        TaskOutputDataset output = output(9L, true);
        when(outputs.findByTaskIdAndEnabledTrueOrderById(3L)).thenReturn(List.of(output));
        when(quality.runChecksForTableWithSummary("rtdwh_paimon", "ads", "daily_sales"))
                .thenReturn(summary(0, 0, 0, 0));

        service.recordSuccess(instance());

        verify(productions).save(argThat(value -> "blocked".equals(value.getStatus())));
        verify(tables, never()).save(any());
    }

    private TaskOutputDataset output(Long id, boolean qualityGate) {
        return TaskOutputDataset.builder().id(id).taskId(3L).catalogName("rtdwh_paimon")
                .databaseName("ads").tableName("daily_sales").layer(DwhTableMeta.TableLayer.ads)
                .slaMinutes(60).qualityGateEnabled(qualityGate).enabled(true).build();
    }

    private WorkflowDTO.OutputDatasetRequest request() {
        WorkflowDTO.OutputDatasetRequest request = new WorkflowDTO.OutputDatasetRequest();
        request.setCatalogName("rtdwh_paimon"); request.setDatabaseName("ads"); request.setTableName("daily_sales");
        request.setLayer("ads"); request.setSlaMinutes(60); request.setQualityGateEnabled(true);
        return request;
    }

    private TaskRunInstance instance() {
        return TaskRunInstance.builder().id(21L).taskId(3L).businessDate(LocalDate.of(2026, 8, 21)).build();
    }

    private QualityCheckSummary summary(int total, int passed, int failed, int errors) {
        LocalDateTime now = LocalDateTime.now();
        return new QualityCheckSummary("batch", total, passed, failed, errors, failed + errors, now, now, 1L);
    }
}
