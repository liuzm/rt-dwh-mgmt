package com.rtdwh.service;

import com.rtdwh.entity.SyncTask.TaskType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskCapabilityPolicyTest {
    @Test void uncertifiedScenariosCannotBeCreatedThroughApiAliases() {
        assertThrows(IllegalArgumentException.class, () -> TaskCapabilityPolicy.requireSupported(TaskType.materialized, null));
        assertThrows(IllegalArgumentException.class, () -> TaskCapabilityPolicy.requireSupported(TaskType.etl, "materialized_table"));
        assertThrows(IllegalArgumentException.class, () -> TaskCapabilityPolicy.requireSupported(TaskType.cdc_sync, "kafka_realtime_ingest"));
        assertThrows(IllegalArgumentException.class, () -> TaskCapabilityPolicy.requireSupported(TaskType.etl, "table_realtime_sync"));
    }
    @Test void ordinarySqlAndExistingScenarioLessDefinitionsRemainSupported() {
        assertDoesNotThrow(() -> TaskCapabilityPolicy.requireSupported(TaskType.etl, "scheduled_sql_output"));
        assertDoesNotThrow(() -> TaskCapabilityPolicy.requireSupported(TaskType.etl, null));
        assertDoesNotThrow(() -> TaskCapabilityPolicy.requireSupported(TaskType.cdc_sync, "database_realtime_sync"));
    }
}
