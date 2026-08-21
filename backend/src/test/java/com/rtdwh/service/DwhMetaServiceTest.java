package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DwhMetaServiceTest {

    private final DwhMetaService service = new DwhMetaService(
            null, null, null, null, new ObjectMapper());

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
    }
}
