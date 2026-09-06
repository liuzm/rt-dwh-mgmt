package com.rtdwh.service;

import com.rtdwh.entity.SyncTask;
import java.util.Map;

/** Certified creation scenarios. Existing jobs can still be inspected and stopped. */
public final class TaskCapabilityPolicy {
    private TaskCapabilityPolicy() {}
    private static final Map<String, SyncTask.TaskType> AVAILABLE = Map.of(
            "table_realtime_sync", SyncTask.TaskType.cdc_sync,
            "database_realtime_sync", SyncTask.TaskType.cdc_sync,
            "sql_transform", SyncTask.TaskType.etl,
            "scheduled_sql_output", SyncTask.TaskType.etl);

    public static void requireSupported(SyncTask.TaskType type, String scenario) {
        if (type == SyncTask.TaskType.materialized || "materialized_table".equals(scenario)) {
            throw new IllegalArgumentException("原生物化表尚未完成刷新与恢复验收，暂不开放新建或发布，请使用 Flink SQL 加工");
        }
        if (scenario != null && !scenario.isBlank() && AVAILABLE.get(scenario) != type) {
            throw new IllegalArgumentException("该任务场景尚未开放或与任务类型不匹配");
        }
    }
}
