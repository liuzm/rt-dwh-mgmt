package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.repository.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.sql.*;

class DwhMetaServiceTest {

    private final DwhMetaService service = new DwhMetaService(
            null, null, null, null, new ObjectMapper(), null, null);

    @Test
    void parsesPaimonTwoSchemaDocument() {
        String schema = """
                {
                  "fields": [
                    {"id":0,"name":"id","type":"BIGINT NOT NULL"},
                    {"id":1,"name":"rule_name","type":"STRING"}
                  ],
                  "primaryKeys": ["id"]
                }
                """;

        var columns = service.parseColumnsFromSchemaJson(schema, "id");

        assertEquals(2, columns.size());
        assertEquals("BIGINT", columns.get(0).getColumnType());
        assertEquals(false, columns.get(0).getIsNullable());
        assertEquals(true, columns.get(0).getIsPk());
        assertEquals(true, columns.get(1).getIsNullable());
        assertEquals(0L, columns.get(0).getEngineFieldId());
    }

    @Test void invalidSchemaIsNotAnEmptySuccessfulObservation() {
        assertThrows(IllegalArgumentException.class, () -> service.parseColumnsFromSchemaJson("{}", ""));
        assertThrows(IllegalArgumentException.class, () -> service.parseColumnsFromSchemaJson("{\"fields\":[]}", ""));
        assertThrows(IllegalArgumentException.class, () -> service.parseColumnsFromSchemaJson("[{\"name\":\"id\"}]", ""));
        var nested = service.parseColumnsFromSchemaJson("[{\"id\":2,\"name\":\"detail\",\"type\":{\"type\":\"ROW\",\"fields\":[]}}]", "");
        assertTrue(nested.get(0).getColumnType().contains("ROW"));
    }
    @Test void missingTablePreservesIdentityColumnsAndHistory() {
        var tables = mock(DwhTableMetaRepository.class); var columns = mock(DwhColumnMetaRepository.class);
        var schemas = mock(AssetSchemaService.class);
        var subject = new DwhMetaService(tables,columns,null,null,new ObjectMapper(),null,schemas);
        var existing = DwhTableMeta.builder().id(1L).assetId("stable").paimonDb("ods").paimonTable("events").discoveryStatus("observed").schemaStatus("observed").build();
        when(tables.findAll()).thenReturn(List.of(existing));
        ReflectionTestUtils.invokeMethod(subject,"removeStaleUnifiedTables",Set.of());
        assertEquals("stable",existing.getAssetId()); assertEquals("missing",existing.getDiscoveryStatus()); assertEquals("stale",existing.getSchemaStatus());
        verify(tables).save(existing); verify(tables,never()).delete(any()); verifyNoInteractions(columns,schemas);
    }
    @Test void failedCatalogListingDoesNotDeclareTablesMissing() throws Exception {
        var tables = mock(DwhTableMetaRepository.class); var conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenThrow(new SQLException("temporary failure"));
        var subject = new DwhMetaService(tables,null,null,null,new ObjectMapper(),null,null);
        ReflectionTestUtils.invokeMethod(subject,"removeStaleTables",List.of("ods"),conn,"catalog_table");
        verifyNoInteractions(tables);
    }
    @Test void declaredButNeverObservedAssetRemainsUnverified() {
        var tables = mock(DwhTableMetaRepository.class);
        var subject = new DwhMetaService(tables,null,null,null,new ObjectMapper(),null,null);
        var declared = DwhTableMeta.builder().id(1L).paimonDb("ads").paimonTable("future").build();
        when(tables.findAll()).thenReturn(List.of(declared));
        ReflectionTestUtils.invokeMethod(subject,"removeStaleUnifiedTables",Set.of());
        assertEquals("unverified",declared.getDiscoveryStatus()); verify(tables,never()).save(any());
    }
}
