package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServiceGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesSqlGatewayJsonRowsAndColumns() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "columns": [
                    {"name": "id", "logicalType": {"type": "BIGINT"}},
                    {"name": "enabled", "logicalType": {"type": "BOOLEAN"}},
                    {"name": "name", "logicalType": {"type": "VARCHAR"}}
                  ],
                  "data": [
                    {"kind": "INSERT", "fields": [1, true, "rule-a"]},
                    {"kind": "INSERT", "fields": [2, false, null]}
                  ]
                }
                """);
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        QueryService.appendGatewayPayload(response, columns, rows, 10);

        assertEquals(List.of("id", "enabled", "name"), columns);
        assertEquals(2, rows.size());
        assertEquals(List.of(1L, true, "rule-a"), rows.get(0));
        assertEquals(2L, rows.get(1).get(0));
        assertEquals(false, rows.get(1).get(1));
        assertEquals(null, rows.get(1).get(2));
    }

    @Test
    void extractsUsefulGatewayRootCause() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {"errors": ["Internal error\\nCaused by: validation failed for ods.table_a"]}
                """);

        String error = QueryService.extractGatewayError(response);

        assertTrue(error.startsWith("Caused by:"));
        assertTrue(error.contains("ods.table_a"));
    }
}
