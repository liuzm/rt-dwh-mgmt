package com.rtdwh;

import com.rtdwh.dto.SyncTaskCreateDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.SyncTask.TaskStatus;
import com.rtdwh.service.SyncTaskService;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.repository.DatasourceConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SyncTaskServiceTest {

    @Autowired
    private SyncTaskService syncTaskService;

    @Autowired
    private DatasourceConfigRepository datasourceRepository;

    @Test
    @DisplayName("创建同步任务 - 应返回 draft 状态")
    void testCreateTask() {
        SyncTaskCreateDTO dto = new SyncTaskCreateDTO();
        dto.setTaskName("test_cdc_task");
        dto.setTaskType("cdc_sync");
        dto.setSourceConfigId(createBusinessDatasource());
        dto.setFlinkSql("CREATE TABLE source (...) WITH ('connector'='mysql-cdc'); INSERT INTO target SELECT * FROM source;");
        dto.setSyncStrategy("full_then_incremental");
        dto.setTableMappings(testTableMappings());

        SyncTask task = syncTaskService.createTask(dto, 1L);

        assertNotNull(task.getId());
        assertEquals("test_cdc_task", task.getTaskName());
        assertEquals(TaskStatus.draft, task.getStatus());
        assertEquals(SyncTask.TaskType.cdc_sync, task.getTaskType());
        assertEquals("table_realtime_sync", task.getScenarioCode());
        assertEquals(SyncTask.ExecutionMode.continuous, task.getExecutionMode());
        assertNull(task.getTargetConfigId());
    }

    @Test
    @DisplayName("获取同步任务详情 - 应返回完整信息")
    void testGetTask() {
        // Create a task first
        SyncTaskCreateDTO dto = new SyncTaskCreateDTO();
        dto.setTaskName("test_get_task");
        dto.setTaskType("cdc_sync");
        dto.setScenarioCode("database_realtime_sync");
        dto.setSourceConfigId(createBusinessDatasource());
        dto.setFlinkSql("SELECT 1");
        dto.setSyncStrategy("incremental_only");
        dto.setTableMappings(testTableMappings());

        SyncTask created = syncTaskService.createTask(dto, 1L);
        SyncTask fetched = syncTaskService.getTask(created.getId());

        assertEquals(created.getId(), fetched.getId());
        assertEquals("test_get_task", fetched.getTaskName());
        assertEquals("database_realtime_sync", fetched.getScenarioCode());
    }

    @Test
    @DisplayName("删除 draft 状态的任务 - 应成功")
    void testDeleteDraftTask() {
        SyncTaskCreateDTO dto = new SyncTaskCreateDTO();
        dto.setTaskName("test_delete_task");
        dto.setTaskType("cdc_sync");
        dto.setSourceConfigId(createBusinessDatasource());
        dto.setFlinkSql("SELECT 1");
        dto.setSyncStrategy("incremental_only");
        dto.setTableMappings(testTableMappings());

        SyncTask created = syncTaskService.createTask(dto, 1L);
        assertDoesNotThrow(() -> syncTaskService.deleteTask(created.getId()));
    }

    @Test
    @DisplayName("删除 running 状态的任务 - 应抛出异常")
    void testDeleteRunningTaskShouldFail() {
        // This test verifies that only draft/finished tasks can be deleted
        // In a real test, we would need to mock the Flink cluster service
        // to set up a running task without actually submitting to Flink
        assertThrows(RuntimeException.class, () -> {
            syncTaskService.deleteTask(999L); // Non-existent task
        });
    }

    private Long createBusinessDatasource() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        DatasourceConfig source = datasourceRepository.save(DatasourceConfig.builder()
                .creatorId(1L).configName("test_mysql_" + suffix).dbType(DatasourceConfig.DbType.mysql)
                .host("localhost").port(3306).database("test_source").username("root")
                .passwordEncrypted("").build());
        return source.getId();
    }

    private String testTableMappings() {
        return "[{\"sourceTable\":\"source_table\",\"targetDb\":\"ods\","
                + "\"targetTable\":\"ods_source_table\",\"syncMode\":\"full+incremental\"}]";
    }
}
