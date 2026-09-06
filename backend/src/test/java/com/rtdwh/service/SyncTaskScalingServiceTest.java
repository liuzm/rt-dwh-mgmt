package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.SyncTaskRepository;
import com.rtdwh.repository.TaskDependencyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncTaskScalingServiceTest {

    @Mock private SyncTaskRepository syncTaskRepository;
    @Mock private TaskDependencyRepository taskDependencyRepository;
    @Mock private FlinkClusterService flinkClusterService;
    @Mock private AlertNotifyService alertNotifyService;
    @Mock private CdcSqlGenerator cdcSqlGenerator;
    @Mock private DatasourceService datasourceService;
    @Mock private ObjectMapper objectMapper;
    @Mock private PostgresCdcService postgresCdcService;

    @InjectMocks private SyncTaskService syncTaskService;

    @Test
    void submitsGuardedParallelismChangeForRunningTask() {
        SyncTask task = runningTask();
        when(syncTaskRepository.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(flinkClusterService.rescaleJob(task.getFlinkJobId(), 4)).thenReturn(Map.of(
                "accepted", true,
                "jobId", task.getFlinkJobId(),
                "targetParallelism", 4
        ));

        Map<String, Object> result = syncTaskService.rescaleTask(
                task.getId(), 4, task.getFlinkJobId(), 2, "业务高峰", "admin");

        assertEquals(true, result.get("accepted"));
        assertEquals(4, result.get("targetParallelism"));
        assertEquals(4, result.get("configuredParallelism"));
        assertEquals(4, task.getParallelism());
        assertEquals("业务高峰", result.get("reason"));
        verify(flinkClusterService).rescaleJob(task.getFlinkJobId(), 4);
        verify(syncTaskRepository).saveAndFlush(task);
    }

    @Test
    void rejectsStaleJobIdBeforeCallingFlink() {
        SyncTask task = runningTask();
        when(syncTaskRepository.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

        assertThrows(IllegalStateException.class, () -> syncTaskService.rescaleTask(
                task.getId(), 4, "old-job-id", 2, "业务高峰", "admin"));

        verify(flinkClusterService, never()).rescaleJob(task.getFlinkJobId(), 4);
    }

    @Test
    void rejectsLegacyMultiInsertTaskBeforeCallingFlink() {
        SyncTask task = runningTask();
        task.setFlinkSql("""
                INSERT INTO `ods`.`orders` SELECT * FROM `src_orders`;
                INSERT INTO `ods`.`items` SELECT * FROM `src_items`;
                """);
        when(syncTaskRepository.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> syncTaskService.rescaleTask(
                        task.getId(), 4, task.getFlinkJobId(), 2, "业务高峰", "admin"));

        assertEquals(true, exception.getMessage().contains("多个 Flink Job"));
        verify(flinkClusterService, never()).rescaleJob(task.getFlinkJobId(), 4);
    }

    @Test
    void rejectsStaleConfiguredParallelismBeforeCallingFlink() {
        SyncTask task = runningTask();
        task.setParallelism(4);
        when(syncTaskRepository.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> syncTaskService.rescaleTask(
                        task.getId(), 8, task.getFlinkJobId(), 2, "再次扩容", "admin"));

        assertEquals(true, exception.getMessage().contains("其他操作修改"));
        verify(flinkClusterService, never()).rescaleJob(task.getFlinkJobId(), 8);
    }

    private SyncTask runningTask() {
        return SyncTask.builder()
                .id(9L)
                .creatorId(1L)
                .taskName("orders_cdc")
                .taskType(SyncTask.TaskType.cdc_sync)
                .status(SyncTask.TaskStatus.running)
                .flinkJobId("0123456789abcdef0123456789abcdef")
                .parallelism(2)
                .build();
    }
}
