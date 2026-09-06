package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.DataServiceDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServicePublicationTest {
    final DataServiceDefinitionRepository definitions = mock(DataServiceDefinitionRepository.class);
    final DataServiceVersionRepository versions = mock(DataServiceVersionRepository.class);
    final DataServiceAppRepository apps = mock(DataServiceAppRepository.class);
    final DataServiceGrantRepository grants = mock(DataServiceGrantRepository.class);
    final DataServiceInvocationLogRepository logs = mock(DataServiceInvocationLogRepository.class);
    final ObjectMapper mapper = new ObjectMapper();
    final QueryService queries = mock(QueryService.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    final DataServiceContractService contracts = mock(DataServiceContractService.class);
    final DataServiceService service = new DataServiceService(definitions, apps, grants, logs,
            new ReportParameterRenderer(mapper), queries, encoder, access, versions, contracts);
    final Map<Long, DataServiceVersion> history = new HashMap<>();
    final String parameters = "{\"parameters\":[{\"name\":\"region\",\"type\":\"string\",\"defaultValue\":\"east\"}]}";
    final DataServiceDefinition definition = DataServiceDefinition.builder().id(2L).creatorId(7L).serviceCode("order-summary")
            .serviceName("Orders").sqlTemplate("SELECT amount FROM rtdwh_paimon.ads.orders WHERE region={{region}}")
            .parameterConfig(parameters).catalogName("rtdwh_paimon").databaseName("ads")
            .maxRows(100).timeoutSeconds(30).rateLimitPerMinute(10).status(DataServiceDefinition.ServiceStatus.draft).build();
    final List<DataServiceContractService.Column> columns = List.of(new DataServiceContractService.Column("amount", "BIGINT", 19, 0, true));

    @BeforeEach void setup() throws Exception {
        when(definitions.findById(2L)).thenReturn(Optional.of(definition));
        when(definitions.findByIdForUpdate(2L)).thenReturn(Optional.of(definition));
        when(definitions.findByServiceCode("order-summary")).thenReturn(Optional.of(definition));
        when(definitions.findAll()).thenReturn(List.of(definition));
        when(definitions.saveAndFlush(any())).thenAnswer(i -> {
            DataServiceDefinition value = i.getArgument(0); value.setRevision(value.getRevision() + 1); return value;
        });
        when(versions.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(history.get(i.getArgument(0))));
        when(versions.findByServiceIdOrderByVersionNoDesc(2L)).thenAnswer(i -> history.values().stream()
                .filter(v -> v.getServiceId().equals(2L)).sorted(Comparator.comparing(DataServiceVersion::getVersionNo).reversed()).toList());
        when(versions.findFirstByServiceIdOrderByVersionNoDesc(2L)).thenAnswer(i -> history.values().stream()
                .filter(v -> v.getServiceId().equals(2L)).max(Comparator.comparing(DataServiceVersion::getVersionNo)));
        when(versions.saveAndFlush(any())).thenAnswer(i -> {
            DataServiceVersion value = i.getArgument(0); value.setId(10L + value.getVersionNo()); history.put(value.getId(), value); return value;
        });
        when(access.isAdmin(1L)).thenReturn(true);
        when(access.canAccessDorisSql(anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(contracts.json(any())).thenAnswer(i -> mapper.writeValueAsString(i.getArgument(0)));
        when(contracts.sameJson(any(), any())).thenAnswer(i -> Objects.equals(i.getArgument(0), i.getArgument(1)));
        when(contracts.columns(anyString())).thenReturn(columns);
        when(contracts.inspect(any(), anyLong())).thenReturn(new DataServiceContractService.Inspection(columns,
                List.of(new ViewSqlService.Name("rtdwh_paimon", "ads", "orders"))));
        when(contracts.breakingChanges(any(), any(), anyList(), anyList())).thenReturn(List.of());
    }
    DataServiceDTO.PublicationRequest request(long revision) {
        var request = new DataServiceDTO.PublicationRequest(); request.setExpectedRevision(revision); return request;
    }
    DataServiceDTO.DefinitionRequest draft(String sql) {
        var request = new DataServiceDTO.DefinitionRequest(); request.setExpectedRevision(definition.getRevision());
        request.setServiceCode(definition.getServiceCode()); request.setServiceName(definition.getServiceName());
        request.setSqlTemplate(sql); request.setParameterConfig(parameters); request.setDatabaseName("ads"); return request;
    }
    DataServiceVersion published(int number) throws Exception {
        var version = DataServiceVersion.builder().id(10L + number).serviceId(2L).versionNo(number).creatorId(7L)
                .serviceCode(definition.getServiceCode()).serviceName(definition.getServiceName()).sqlTemplate(definition.getSqlTemplate())
                .parameterConfig(definition.getParameterConfig()).catalogName("rtdwh_paimon").databaseName("ads")
                .maxRows(100).timeoutSeconds(30).rateLimitPerMinute(10).resultColumnsJson(mapper.writeValueAsString(columns))
                .sourceRevision(0L).origin("publish").createdAt(LocalDateTime.now()).build();
        history.put(version.getId(), version); definition.setPublishedVersionId(version.getId());
        definition.setStatus(DataServiceDefinition.ServiceStatus.published); definition.setApiVersion(number); return version;
    }
    void grantedApp() {
        when(apps.findByAppKey("key")).thenReturn(Optional.of(DataServiceApp.builder().id(5L).appKey("key").secretHash("hash").enabled(true).build()));
        when(encoder.matches("secret", "hash")).thenReturn(true); when(grants.existsByAppIdAndServiceId(5L, 2L)).thenReturn(true);
    }
    Map<String, Object> result() { return Map.of("status", "success", "columns", List.of("amount"), "columnSchema", columns,
            "rows", List.of(List.of(9)), "rowCount", 1, "truncated", false, "durationMs", 12L, "requestId", "request-1"); }

    @Test void sharedScopeDoesNotGiveWriteOwnership() {
        assertEquals(1, service.definitions(8L).size()); assertFalse(service.definitions(8L).get(0).getManageable());
        assertThrows(AccessDeniedException.class, () -> service.publish(2L, true, 8L, request(0)));
        assertThrows(AccessDeniedException.class, () -> service.updateDefinition(2L, draft(definition.getSqlTemplate()), 8L));
    }
    @Test void editingOnlyChangesDraftAndDoesNotChangePublishedNumber() throws Exception {
        var original = published(3);
        var edited = service.updateDefinition(2L, draft("SELECT amount * 2 FROM rtdwh_paimon.ads.orders WHERE region={{region}}"), 7L);
        assertEquals(3, edited.getApiVersion()); assertEquals(original.getId(), edited.getPublishedVersionId());
        assertTrue(edited.getHasDraftChanges()); assertEquals(1L, edited.getRevision());
        assertFalse(original.getSqlTemplate().contains("* 2")); verify(versions, never()).saveAndFlush(any());
    }
    @Test void staleOrMissingRevisionCannotWriteOrPublish() {
        definition.setRevision(2L);
        assertThrows(IllegalStateException.class, () -> service.publish(2L, true, 7L, request(1)));
        var request = draft(definition.getSqlTemplate()); request.setExpectedRevision(null);
        assertThrows(IllegalArgumentException.class, () -> service.updateDefinition(2L, request, 7L));
        verifyNoInteractions(contracts); verify(versions, never()).saveAndFlush(any());
    }
    @Test void publicationFreezesCompleteDefinitionAndSwitchesPointer() {
        var published = service.publish(2L, true, 7L, request(0)); var version = history.get(published.getPublishedVersionId());
        assertEquals(1, version.getVersionNo()); assertEquals(parameters, version.getParameterConfig());
        assertEquals(0L, version.getSourceRevision()); assertEquals(7L, version.getPublishedBy());
        assertNotNull(version.getDependenciesJson()); assertNotNull(version.getResultColumnsJson());
        assertEquals(1L, published.getRevision()); assertFalse(published.getHasDraftChanges());
    }
    @Test void failedMetadataPreflightDoesNotCreateVersionOrChangePointer() throws Exception {
        var version = published(3);
        when(contracts.inspect(any(), anyLong())).thenThrow(new IllegalArgumentException("Doris unavailable"));
        assertThrows(IllegalArgumentException.class, () -> service.publish(2L, true, 7L, request(0)));
        assertEquals(version.getId(), definition.getPublishedVersionId()); assertEquals(1, history.size());
        verify(definitions, never()).saveAndFlush(any()); verify(versions, never()).saveAndFlush(any());
    }
    @Test void incompatiblePublicationIsRejectedWhilePreviewExplainsWhy() throws Exception {
        published(3); when(contracts.breakingChanges(any(), any(), anyList(), anyList())).thenReturn(List.of("输出列不兼容"));
        assertFalse(service.preview(2L, 7L, request(0)).publishable());
        assertThrows(IllegalStateException.class, () -> service.publish(2L, true, 7L, request(0)));
        assertEquals(3, definition.getApiVersion()); verify(versions, never()).saveAndFlush(any());
    }
    @Test void rollbackAppendsReleaseAndKeepsDraft() throws Exception {
        var original = published(3); definition.setSqlTemplate("SELECT amount * 2 FROM orders WHERE region={{region}}");
        var rolled = service.rollback(2L, original.getId(), 7L, request(0)); var release = history.get(rolled.getPublishedVersionId());
        assertEquals(4, release.getVersionNo()); assertEquals("rollback", release.getOrigin());
        assertEquals(original.getId(), release.getSourceVersionId()); assertEquals(original.getSqlTemplate(), release.getSqlTemplate());
        assertTrue(rolled.getSqlTemplate().contains("* 2")); assertTrue(rolled.getHasDraftChanges());
    }
    @Test void foreignVersionCannotBeRolledBack() throws Exception {
        var foreign = published(3); foreign.setServiceId(999L); definition.setPublishedVersionId(null);
        assertThrows(IllegalArgumentException.class, () -> service.rollback(2L, foreign.getId(), 7L, request(0)));
        verify(versions, never()).saveAndFlush(any());
    }
    @Test void invocationUsesPublishedParametersLimitsAndIdentityDespiteDraftEdit() throws Exception {
        var version = published(3); grantedApp();
        definition.setSqlTemplate("SELECT secret FROM payroll"); definition.setParameterConfig("[]"); definition.setMaxRows(999);
        when(queries.executeDataServiceQuery(anyString(), eq(7L), eq("rtdwh_paimon"), eq("ads"), eq(100), eq(30))).thenReturn(result());
        var response = service.invoke("order-summary", "key", "secret", Map.of(), "127.0.0.1");
        assertEquals(3, response.get("apiVersion")); assertEquals(version.getId(), response.get("versionId"));
        verify(queries).executeDataServiceQuery(contains("region='east'"), eq(7L), eq("rtdwh_paimon"), eq("ads"), eq(100), eq(30));
        verify(logs).save(argThat(log -> version.getId().equals(log.getVersionId()) && log.getExecutionUserId() == 7L && log.getHttpStatus() == 200));
    }
    @Test void inFlightInvocationKeepsItsVersionWhenPointerChanges() throws Exception {
        var version = published(3); grantedApp();
        when(queries.executeDataServiceQuery(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyInt())).thenAnswer(i -> {
            definition.setPublishedVersionId(99L); definition.setApiVersion(4); return result();
        });
        var response = service.invoke("order-summary", "key", "secret", Map.of(), "127.0.0.1");
        assertEquals(3, response.get("apiVersion")); assertEquals(version.getId(), response.get("versionId"));
        verify(logs).save(argThat(log -> version.getId().equals(log.getVersionId())));
    }
    @Test void resultDriftDoesNotReturnRowsAndLeavesFailedVersionEvidence() throws Exception {
        var version = published(3); grantedApp();
        when(queries.executeDataServiceQuery(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(result());
        doThrow(new IllegalStateException("结果契约变化")).when(contracts).validateResult(eq(version), any());
        assertThrows(IllegalStateException.class, () -> service.invoke("order-summary", "key", "secret", Map.of(), "127.0.0.1"));
        verify(logs, times(1)).save(argThat(log -> "failed".equals(log.getStatus()) && log.getHttpStatus() == 409 && version.getId().equals(log.getVersionId())));
    }
    @Test void historiesUseFrozenScopeRatherThanRetargetedDraft() throws Exception {
        var version = published(3); definition.setSqlTemplate("SELECT amount FROM public_table"); definition.setParameterConfig("[]");
        when(access.canAccessDorisSql(eq(7L), contains("orders"), anyString(), anyString())).thenReturn(false);
        when(logs.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(
                DataServiceInvocationLog.builder().serviceId(2L).versionId(version.getId()).build(), DataServiceInvocationLog.builder().serviceId(2L).build()));
        assertTrue(service.versions(2L, 7L).isEmpty()); assertTrue(service.logs(100, 7L).isEmpty());
        assertTrue(service.definitions(7L).isEmpty()); assertEquals(2, service.logs(100, 1L).size());
    }
    @Test void offlineServiceCannotInvokeButVersionEvidenceRemains() throws Exception {
        var version = published(3); service.publish(2L, false, 7L, request(0));
        assertEquals(version.getId(), definition.getPublishedVersionId());
        assertThrows(IllegalArgumentException.class, () -> service.invoke("order-summary", "key", "secret", Map.of(), "127.0.0.1"));
        verifyNoInteractions(queries);
    }
}
