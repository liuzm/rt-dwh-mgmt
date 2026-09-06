package com.rtdwh.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlAssetReferenceServiceTest {
    final SqlAssetReferenceService service = new SqlAssetReferenceService();
    @Test void insertSelectRetainsCatalogAndDirection() {
        var result = service.inspect("USE CATALOG rtdwh_paimon; USE dwd; INSERT INTO fact SELECT * FROM rtdwh_paimon.ods.events", "other", "default");
        assertTrue(result.parsed());
        assertTrue(result.references().stream().anyMatch(r -> r.catalog().equals("rtdwh_paimon") && r.database().equals("dwd") && r.table().equals("fact") && r.output()));
        assertTrue(result.references().stream().anyMatch(r -> r.database().equals("ods") && r.table().equals("events") && r.input()));
    }
    @Test void commentsLiteralsAndCteNamesAreNotPhysicalAssets() {
        var result = service.inspect("-- FROM secret.hidden\nWITH c AS (SELECT * FROM ods.events) SELECT 'FROM secret.fake' FROM c", "rtdwh_paimon", "ods");
        assertTrue(result.parsed()); assertEquals(1, result.references().size()); assertEquals("events", result.references().get(0).table());
    }
    @Test void unsupportedStatementsDiscardPartialReferences() {
        var result = service.inspect("SELECT * FROM ods.events; CREATE TEMPORARY TABLE sink (id INT) WITH ('connector'='blackhole')", "rtdwh_paimon", "ods");
        assertFalse(result.parsed()); assertTrue(result.references().isEmpty());
    }
    @Test void distinctCatalogIsPreserved() {
        var result = service.inspect("SELECT * FROM external.ods.events", "rtdwh_paimon", "ods");
        assertTrue(result.parsed()); assertEquals("external", result.references().get(0).catalog());
    }
}
