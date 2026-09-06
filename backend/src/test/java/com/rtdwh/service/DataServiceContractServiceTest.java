package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServiceContractServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final DorisConnectionService doris = mock(DorisConnectionService.class);
    final QueryAccessScopeService access = mock(QueryAccessScopeService.class);
    final ReportParameterRenderer renderer = new ReportParameterRenderer(mapper);
    final DataServiceContractService contracts = new DataServiceContractService(doris, renderer, access, new ViewSqlService(), mapper);
    final Connection connection = mock(Connection.class);
    final Statement statement = mock(Statement.class);
    final ResultSet result = mock(ResultSet.class);
    final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    final DataServiceDefinition definition = DataServiceDefinition.builder().creatorId(7L).sqlTemplate("SELECT amount FROM orders")
            .catalogName("rtdwh_paimon").databaseName("ads").build();
    final List<DataServiceContractService.Column> columns = List.of(new DataServiceContractService.Column("amount", "BIGINT", 19, 0, false));

    @BeforeEach void setup() throws Exception {
        when(doris.getConnection()).thenReturn(connection); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result); when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1); when(metadata.getColumnLabel(1)).thenReturn("amount");
        when(metadata.getColumnTypeName(1)).thenReturn("BIGINT"); when(metadata.getPrecision(1)).thenReturn(19);
        when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
    }
    @Test void inspectsMetadataAndFreezesCanonicalDependenciesWithoutReadingRows() throws Exception {
        var inspection = contracts.inspect(definition, 1L);
        assertEquals(columns, inspection.columns());
        assertEquals(List.of(new ViewSqlService.Name("rtdwh_paimon", "ads", "orders")), inspection.dependencies());
        verify(access).validateDoris(1L, definition.getSqlTemplate(), "rtdwh_paimon", "ads");
        verify(access).validateDoris(7L, definition.getSqlTemplate(), "rtdwh_paimon", "ads");
        verify(statement).executeQuery(endsWith("LIMIT 0")); verify(result, never()).next();
    }
    @Test void administratorPublicationStillChecksExecutionOwnersCurrentPermission() throws Exception {
        doThrow(new IllegalArgumentException("无权访问 orders")).when(access).validateDoris(eq(7L), anyString(), anyString(), anyString());
        assertThrows(IllegalArgumentException.class, () -> contracts.inspect(definition, 1L));
        verify(doris, never()).getConnection();
    }
    @Test void nonSelectCannotBePublishedAsAnApi() throws Exception {
        definition.setSqlTemplate("SHOW TABLES");
        assertThrows(IllegalArgumentException.class, () -> contracts.inspect(definition, 7L));
        verify(doris, never()).getConnection();
    }
    @Test void duplicateOutputAliasesAreRejected() throws Exception {
        when(metadata.getColumnCount()).thenReturn(2); when(metadata.getColumnLabel(2)).thenReturn("AMOUNT");
        assertThrows(IllegalArgumentException.class, () -> contracts.inspect(definition, 7L));
    }
    @Test void runtimeSchemaChangesCannotReturnUncontractedData() {
        var version = DataServiceVersion.builder().origin("publish").resultColumnsJson(contracts.json(columns)).build();
        contracts.validateResult(version, columns);
        for (var column : List.of(new DataServiceContractService.Column("renamed", "BIGINT", 19, 0, false),
                new DataServiceContractService.Column("amount", "VARCHAR", 19, 0, false),
                new DataServiceContractService.Column("amount", "BIGINT", 19, 0, true))) {
            assertThrows(IllegalStateException.class, () -> contracts.validateResult(version, List.of(column)));
        }
        assertThrows(IllegalStateException.class, () -> contracts.validateResult(version, null));
    }
    @Test void onlyExplicitLegacyCaptureMayLackResultEvidence() {
        contracts.validateResult(DataServiceVersion.builder().origin("legacy_capture").build(), columns);
        assertThrows(IllegalStateException.class, () -> contracts.validateResult(DataServiceVersion.builder().origin("publish").build(), columns));
    }
    @Test void parameterCompatibilityProtectsExistingCallers() {
        var version = DataServiceVersion.builder().parameterConfig("[{\"name\":\"region\",\"type\":\"string\"}]").build();
        for (String config : List.of("[]", "[{\"name\":\"region\",\"type\":\"number\"}]",
                "[{\"name\":\"region\",\"type\":\"string\",\"required\":true}]",
                "[{\"name\":\"region\",\"type\":\"string\"},{\"name\":\"extra\",\"required\":true}]")) {
            definition.setParameterConfig(config);
            assertFalse(contracts.breakingChanges(version, definition, columns, columns).isEmpty());
        }
        definition.setParameterConfig("[{\"name\":\"region\",\"type\":\"string\",\"defaultValue\":\"west\"},{\"name\":\"optional\"}]");
        assertTrue(contracts.breakingChanges(version, definition, columns, columns).isEmpty());
    }
    @Test void listParameterInspectionUsesValidSqlAndPreservesRuntimeDefaults() {
        String config = "[{\"name\":\"ids\",\"type\":\"stringList\",\"required\":true}]";
        String sql = "SELECT amount FROM orders WHERE id IN {{ids}}";
        assertEquals("SELECT amount FROM orders WHERE id IN (NULL)", renderer.sqlForAccessCheck(sql, config));
        assertEquals("SELECT amount FROM orders WHERE id IN ('contract')", renderer.sqlForContractCheck(sql, config));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(sql, config, Map.of()));
    }
    @Test void requiredParameterCannotLoseItsDefaultAndBreakCallersThatOmitIt() {
        var version = DataServiceVersion.builder().parameterConfig("[{\"name\":\"region\",\"required\":true,\"defaultValue\":\"east\"}]").build();
        for (String suffix : List.of("", ",\"defaultValue\":null", ",\"defaultValue\":\" \"")) {
            definition.setParameterConfig("[{\"name\":\"region\",\"required\":true" + suffix + "}]");
            assertFalse(contracts.breakingChanges(version, definition, columns, columns).isEmpty());
        }
        definition.setParameterConfig("[{\"name\":\"region\",\"required\":true,\"defaultValue\":\"west\"}]");
        assertTrue(contracts.breakingChanges(version, definition, columns, columns).isEmpty());
        definition.setParameterConfig("[{\"name\":\"region\",\"required\":false}]");
        assertTrue(contracts.breakingChanges(version, definition, columns, columns).isEmpty());
    }
    @Test void jsonWhitespaceAndKeyOrderingDoNotCreateDraftChanges() {
        assertTrue(contracts.sameJson("{\"a\":1,\"b\":2}", "{ \"b\": 2, \"a\": 1 }"));
    }
}
