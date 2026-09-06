package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportScheduleConfigTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCronTimezoneAndLimits() {
        ReportScheduleConfig config = ReportScheduleConfig.parse(
                "{\"enabled\":true,\"cron\":\"0 */5 * * * *\",\"timezone\":\"Asia/Shanghai\","
                        + "\"retainCount\":500,\"maxRows\":9000,\"maxRetries\":9,"
                        + "\"notifyOn\":\"failure\",\"notifyChannels\":[\"email\"],"
                        + "\"recipients\":\"owner@example.com\",\"parameters\":{\"region\":\"east\"}}", objectMapper);

        assertTrue(config.enabled());
        assertEquals(200, config.retainCount());
        assertEquals(5000, config.maxRows());
        assertEquals(3, config.maxRetries());
        assertTrue(config.shouldNotify("failed"));
        assertEquals(List.of("owner@example.com"), config.recipients());
        assertEquals(Map.of("region", "east"), config.parameters());
        assertEquals(LocalDateTime.of(2026, 8, 22, 10, 5),
                config.nextAfter(LocalDateTime.of(2026, 8, 22, 10, 1)));
    }

    @Test
    void rejectsInvalidCron() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportScheduleConfig.parse(
                        "{\"enabled\":true,\"cron\":\"not-a-cron\"}", objectMapper));
    }
}
