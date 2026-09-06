package com.rtdwh.service;

import com.rtdwh.dto.FoundationDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoundationServiceTest {
    private final DwhTableMetaRepository tables = mock(DwhTableMetaRepository.class);
    private final SyncTaskRepository tasks = mock(SyncTaskRepository.class);
    private final ReportTemplateRepository reports = mock(ReportTemplateRepository.class);
    private final DataServiceDefinitionRepository dataServices = mock(DataServiceDefinitionRepository.class);
    private final TaskOutputDatasetRepository outputs = mock(TaskOutputDatasetRepository.class);
    private final TaskScheduleRepository schedules = mock(TaskScheduleRepository.class);
    private final QualityRuleRepository qualityRules = mock(QualityRuleRepository.class);
    private final QualityAlertRepository qualityAlerts = mock(QualityAlertRepository.class);
    private final SystemHealthStatusRepository health = mock(SystemHealthStatusRepository.class);
    private final AlertRecordRepository alerts = mock(AlertRecordRepository.class);
    private final SysUserRepository users = mock(SysUserRepository.class);
    private final RoleDataScopeRepository scopes = mock(RoleDataScopeRepository.class);
    private final OperationAuditRepository audits = mock(OperationAuditRepository.class);
    private final TaskDefinitionVersionRepository versions = mock(TaskDefinitionVersionRepository.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final FoundationService service = new FoundationService(tables, tasks, reports, dataServices,
            outputs, schedules, qualityRules, qualityAlerts, health, alerts, users, scopes, audits, versions, access,
            new com.fasterxml.jackson.databind.ObjectMapper(), mock(DataServiceService.class));

    @Test
    void buildsFiveCapabilitySummaryFromExistingGovernanceData() {
        SysPermission permission = SysPermission.builder().permCode("dwh:view").build();
        SysRole role = SysRole.builder().id(2L).roleCode("DEVELOPER").permissions(Set.of(permission)).build();
        when(users.findById(7L)).thenReturn(Optional.of(SysUser.builder().id(7L).roles(Set.of(role)).build()));
        when(scopes.findByRoleIdIn(anyCollection())).thenReturn(List.of(RoleDataScope.builder().id(1L).build()));
        List<DwhTableMeta> visible = List.of(
                DwhTableMeta.builder().id(1L).paimonDb("ads").paimonTable("sales").layer(DwhTableMeta.TableLayer.ads).owner("data").build(),
                DwhTableMeta.builder().id(2L).paimonDb("dws").paimonTable("customer").layer(DwhTableMeta.TableLayer.dws).build());
        when(tables.findAll()).thenReturn(visible);
        when(access.filterAllowed(eq(7L), anyCollection(), any(), any(), any())).thenAnswer(invocation -> List.copyOf(invocation.getArgument(1)));
        when(reports.countByIsPublishedTrue()).thenReturn(2L);
        when(dataServices.countByStatus(DataServiceDefinition.ServiceStatus.published)).thenReturn(1L);
        when(qualityRules.countByEnabledTrue()).thenReturn(3L);
        when(qualityAlerts.countByResolvedFalse()).thenReturn(1L);
        when(alerts.countByResolvedFalse()).thenReturn(1L);

        FoundationDTO.Summary summary = service.summary(7L, true, true, false, false);

        assertEquals(5, summary.capabilities().size());
        FoundationDTO.Capability asset = summary.capabilities().get(0);
        assertEquals(2L, asset.metrics().get("可见数据表"));
        assertEquals(1, asset.riskCount());
        assertTrue(summary.overallScore() < 100);
    }

    @Test
    void reportsOnlyOverdueOutputsFromEnabledSchedules() {
        TaskSchedule schedule = TaskSchedule.builder().taskId(3L).enabled(true).build();
        TaskOutputDataset overdue = TaskOutputDataset.builder().id(9L).taskId(3L).catalogName("rtdwh_paimon")
                .databaseName("ads").tableName("daily_sales").layer(DwhTableMeta.TableLayer.ads)
                .slaMinutes(60).enabled(true).createdAt(LocalDateTime.now().minusMinutes(180)).build();
        TaskOutputDataset unscheduled = TaskOutputDataset.builder().id(10L).taskId(4L).catalogName("rtdwh_paimon")
                .databaseName("ads").tableName("weekly_sales").layer(DwhTableMeta.TableLayer.ads)
                .slaMinutes(60).enabled(true).createdAt(LocalDateTime.now().minusDays(2)).build();
        when(schedules.findByEnabledTrue()).thenReturn(List.of(schedule));
        when(outputs.findByEnabledTrueOrderByLastProducedAtAsc()).thenReturn(List.of(overdue, unscheduled));
        when(access.filterAllowed(eq(7L), anyCollection(), any(), any(), any()))
                .thenReturn(List.of(overdue, unscheduled));

        List<FoundationDTO.SlaRisk> risks = service.slaRisks(7L);

        assertEquals(1, risks.size());
        assertEquals("rtdwh_paimon.ads.daily_sales", risks.get(0).qualifiedName());
        assertTrue(risks.get(0).overdueMinutes() >= 119);
    }

    @Test
    void doesNotReportSlaRiskWhenLatestScheduleHasProducedSuccessfully() {
        TaskSchedule schedule = TaskSchedule.builder().taskId(3L).enabled(true).timezone("Asia/Shanghai")
                .lastRunAt(java.time.Instant.now().minusSeconds(3600)).build();
        TaskOutputDataset output = TaskOutputDataset.builder().id(9L).taskId(3L).catalogName("rtdwh_paimon")
                .databaseName("ads").tableName("daily_sales").layer(DwhTableMeta.TableLayer.ads)
                .slaMinutes(30).enabled(true).lastProducedAt(LocalDateTime.now().minusMinutes(50)).build();
        when(schedules.findByEnabledTrue()).thenReturn(List.of(schedule));
        when(outputs.findByEnabledTrueOrderByLastProducedAtAsc()).thenReturn(List.of(output));
        when(access.filterAllowed(eq(7L), anyCollection(), any(), any(), any())).thenReturn(List.of(output));

        assertTrue(service.slaRisks(7L).isEmpty());
    }
    @Test
    void searchesEachAssetUsingItsStoredCatalog() {
        var paimon = DwhTableMeta.builder().id(1L).catalogName("rtdwh_paimon").paimonDb("rtdwh_views").paimonTable("same_name").layer(DwhTableMeta.TableLayer.ads).build();
        var view = DwhTableMeta.builder().id(2L).catalogName("internal").paimonDb("rtdwh_views").paimonTable("same_name").assetType("doris_view").layer(DwhTableMeta.TableLayer.ads).build();
        when(tables.searchTables(null,null,"same")).thenReturn(List.of(paimon,view));
        when(access.filterAllowed(eq(7L), anyCollection(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Function<DwhTableMeta,String> catalog = invocation.getArgument(2);
            return ((List<DwhTableMeta>)invocation.getArgument(1)).stream().filter(t -> "rtdwh_paimon".equals(catalog.apply(t))).toList();
        });
        var result=service.search(7L,"same",10,false,false);
        assertEquals(1,result.size());assertEquals(1L,result.get(0).id());
    }

}
