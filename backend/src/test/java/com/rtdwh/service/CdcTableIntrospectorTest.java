package com.rtdwh.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdcTableIntrospectorTest {

    @Test
    void resolvesUtcSystemTimeZone() {
        assertEquals(
                "UTC",
                CdcTableIntrospector.resolveMySqlServerTimeZone("SYSTEM", "UTC", 0));
    }

    @Test
    void preservesNamedSessionTimeZone() {
        assertEquals(
                "Asia/Shanghai",
                CdcTableIntrospector.resolveMySqlServerTimeZone("Asia/Shanghai", "UTC", 28800));
    }

    @Test
    void fallsBackToMeasuredOffsetForAmbiguousSystemZone() {
        assertEquals(
                "+08:00",
                CdcTableIntrospector.resolveMySqlServerTimeZone("SYSTEM", "CST", 28800));
    }
}
