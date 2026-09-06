package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.SyncTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncTaskStatusMonitorTest {

    @Mock
    private SyncTaskRepository syncTaskRepository;
    @Mock
    private FlinkClusterService flinkClusterService;
    @Mock
    private AlertNotifyService alertNotifyService;
    @Mock
    private CdcSqlGenerator cdcSqlGenerator;
    @Mock
    private DatasourceService datasourceService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private SyncTaskService syncTaskService;

    @Test
    void marksRunningTaskFinishedWhenFlinkJobNoLongerExists() {
        SyncTask task = activeTask();
        when(syncTaskRepository.findByStatusIn(any())).thenReturn(List.of(task));
        when(flinkClusterService.getJobStatus(task.getFlinkJobId())).thenReturn(Map.of(
                "status", "NOT_FOUND",
                "flinkState", "NOT_FOUND",
                "lagMs", 0L,
                "throughputQps", 0.0
        ));

        int synced = syncTaskService.syncTaskStatusFromFlink();

        assertEquals(1, synced);
        assertEquals(SyncTask.TaskStatus.finished, task.getStatus());
        assertEquals(0L, task.getCurrentLagMs());
        assertEquals(0.0, task.getThroughputQps());
        verify(syncTaskRepository).save(task);
    }

    @Test
    void keepsRunningTaskWhenFlinkClusterIsTemporarilyUnreachable() {
        SyncTask task = activeTask();
        when(syncTaskRepository.findByStatusIn(any())).thenReturn(List.of(task));
        when(flinkClusterService.getJobStatus(task.getFlinkJobId())).thenReturn(Map.of(
                "status", "UNREACHABLE",
                "lagMs", 0L,
                "throughputQps", 0.0
        ));

        int synced = syncTaskService.syncTaskStatusFromFlink();

        assertEquals(0, synced);
        assertEquals(SyncTask.TaskStatus.running, task.getStatus());
    }

    @Test void completedJobWithPendingSavepointRetainsRequestHandle() {
        SyncTask task = activeTask(); task.setStatus(SyncTask.TaskStatus.saving_point); task.setSavepointTriggerId("request1");
        when(syncTaskRepository.findByStatusIn(any())).thenReturn(List.of(task));
        when(flinkClusterService.getJobStatus(task.getFlinkJobId())).thenReturn(Map.of("flinkState", "FINISHED"));
        when(flinkClusterService.pollSavepointStatus(task.getFlinkJobId(), "request1")).thenReturn(Map.of("status", "IN_PROGRESS"));
        syncTaskService.syncTaskStatusFromFlink();
        assertEquals(SyncTask.TaskStatus.saving_point, task.getStatus()); assertEquals("request1", task.getSavepointTriggerId());
    }

    @Test void finishedStopBecomesPausedWithSavepointWhileManualSavepointStaysRunning() throws Exception {
        for (boolean stopping : List.of(true, false)) {
            SyncTask task = activeTask(); task.setStatus(stopping ? SyncTask.TaskStatus.saving_point : SyncTask.TaskStatus.running);
            task.setSavepointTriggerId("request1");
            when(syncTaskRepository.findByStatusIn(any())).thenReturn(List.of(task));
            when(flinkClusterService.getJobStatus(task.getFlinkJobId())).thenReturn(Map.of("flinkState", stopping ? "FINISHED" : "RUNNING"));
            when(flinkClusterService.pollSavepointStatus(task.getFlinkJobId(), "request1")).thenReturn(Map.of("status", "COMPLETED", "savepointPath", "file:///state/sp"));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"savepointPath\":\"file:///state/sp\"}");
            syncTaskService.syncTaskStatusFromFlink();
            assertEquals(stopping ? SyncTask.TaskStatus.paused : SyncTask.TaskStatus.running, task.getStatus());
            assertEquals("{\"savepointPath\":\"file:///state/sp\"}", task.getCheckpointInfo());
        }
    }

    private SyncTask activeTask() {
        return SyncTask.builder()
                .id(2L)
                .creatorId(1L)
                .taskName("mysql_cdc_paimon")
                .status(SyncTask.TaskStatus.running)
                .flinkJobId("1aa6a5d7a1c65312")
                .currentLagMs(10L)
                .throughputQps(2.0)
                .build();
    }
}
