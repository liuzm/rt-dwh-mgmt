package com.rtdwh.service;

import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServiceServiceTest {
    private final DataServiceDefinitionRepository definitions = mock(DataServiceDefinitionRepository.class);
    private final DataServiceAppRepository apps = mock(DataServiceAppRepository.class);
    private final DataServiceGrantRepository grants = mock(DataServiceGrantRepository.class);
    private final DataServiceInvocationLogRepository logs = mock(DataServiceInvocationLogRepository.class);
    private final ReportParameterRenderer renderer = mock(ReportParameterRenderer.class);
    private final QueryService queryService = mock(QueryService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    private final DataServiceVersionRepository versions = mock(DataServiceVersionRepository.class);
    private final DataServiceContractService contracts = mock(DataServiceContractService.class);
    private final DataServiceService service = new DataServiceService(definitions, apps, grants, logs, renderer, queryService, encoder, access, versions, contracts);

    @Test
    void hiddenDefinitionCannotBePublishedUpdatedDeletedOrListed() {
        DataServiceDefinition definition = DataServiceDefinition.builder().id(2L).serviceCode("private-data")
                .sqlTemplate("select * from payroll").catalogName("rtdwh_paimon").databaseName("ads").build();
        when(definitions.findById(2L)).thenReturn(Optional.of(definition));
        when(definitions.findByIdForUpdate(2L)).thenReturn(Optional.of(definition));
        when(definitions.findAll()).thenReturn(java.util.List.of(definition));
        assertTrue(service.definitions(7L).isEmpty());
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.publish(2L, true, 7L, null));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.updateDefinition(2L, null, 7L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.deleteDefinition(2L, 7L));
        verify(definitions, never()).save(any());
        verify(definitions, never()).delete(any());
    }

    @Test
    void otherUsersAppCannotBeRotatedGrantedRevokedOrListed() {
        DataServiceApp app = DataServiceApp.builder().id(5L).createdBy(8L).build();
        when(apps.findById(5L)).thenReturn(Optional.of(app));
        when(apps.findAll()).thenReturn(java.util.List.of(app));
        assertTrue(service.apps(7L).isEmpty());
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.rotateSecret(5L, 7L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.toggleApp(5L, 7L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.grant(5L, 2L, 7L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.revoke(5L, 2L, 7L));
        verify(apps, never()).save(any());
        verifyNoInteractions(grants);
    }

    @Test
    void authenticatesGrantedAppAndReturnsBoundedQueryResult() {
        DataServiceDefinition definition = DataServiceDefinition.builder().id(2L).serviceCode("order-summary")
                .creatorId(7L).sqlTemplate("select * from orders where region={{region}}")
                .parameterConfig("[]").catalogName("rtdwh_paimon").databaseName("ads")
                .maxRows(100).timeoutSeconds(30).rateLimitPerMinute(10)
                .status(DataServiceDefinition.ServiceStatus.published).apiVersion(3).build();
        DataServiceApp app = DataServiceApp.builder().id(5L).appKey("key").secretHash("hash").enabled(true).build();
        definition.setPublishedVersionId(10L);
        when(versions.findById(10L)).thenReturn(Optional.of(DataServiceVersion.builder().id(10L).serviceId(2L)
                .versionNo(3).creatorId(7L).serviceCode("order-summary").sqlTemplate(definition.getSqlTemplate())
                .parameterConfig("[]").catalogName("rtdwh_paimon").databaseName("ads")
                .maxRows(100).timeoutSeconds(30).rateLimitPerMinute(10).build()));
        when(definitions.findByServiceCode("order-summary")).thenReturn(Optional.of(definition));
        when(apps.findByAppKey("key")).thenReturn(Optional.of(app));
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(grants.existsByAppIdAndServiceId(5L, 2L)).thenReturn(true);
        when(renderer.render(anyString(), anyString(), anyMap())).thenReturn("select * from orders where region='east'");
        when(queryService.executeDataServiceQuery(anyString(), eq(7L), eq("rtdwh_paimon"), eq("ads"), eq(100), eq(30)))
                .thenReturn(Map.of("status", "success", "columns", java.util.List.of("id"),
                        "rows", java.util.List.of(java.util.List.of(1)), "rowCount", 1,
                        "truncated", false, "durationMs", 12L, "requestId", "request-1"));

        Map<String, Object> result = service.invoke("order-summary", "key", "secret", Map.of("region", "east"), "127.0.0.1");

        assertEquals(1, result.get("rowCount"));
        assertEquals(3, result.get("apiVersion"));
        verify(logs).save(argThat(log -> "success".equals(log.getStatus()) && log.getHttpStatus() == 200));
    }

    @Test
    void rejectsAppWithoutServiceGrant() {
        DataServiceDefinition definition = DataServiceDefinition.builder().id(2L).serviceCode("private-data")
                .status(DataServiceDefinition.ServiceStatus.published).rateLimitPerMinute(10).build();
        DataServiceApp app = DataServiceApp.builder().id(5L).appKey("key").secretHash("hash").enabled(true).build();
        when(definitions.findByServiceCode("private-data")).thenReturn(Optional.of(definition));
        when(apps.findByAppKey("key")).thenReturn(Optional.of(app));
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(grants.existsByAppIdAndServiceId(5L, 2L)).thenReturn(false);

        assertThrows(com.rtdwh.exception.DataServiceAuthException.class,
                () -> service.invoke("private-data", "key", "secret", Map.of(), "127.0.0.1"));
    }
}
