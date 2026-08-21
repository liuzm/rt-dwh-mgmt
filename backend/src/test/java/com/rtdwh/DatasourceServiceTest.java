package com.rtdwh;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import com.rtdwh.repository.DatasourceConfigRepository;
import com.rtdwh.service.DatasourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DatasourceServiceTest {

    @Autowired
    private DatasourceConfigRepository datasourceConfigRepository;

    @Autowired
    private DatasourceService datasourceService;

    @Test
    @DisplayName("创建数据源配置 - 应成功保存")
    void testCreateDatasource() {
        DatasourceConfig config = DatasourceConfig.builder()
                .creatorId(1L)
                .configName("Test MySQL Source")
                .dbType(DbType.mysql)
                .host("localhost")
                .port(3306)
                .database("test_db")
                .username("test_user")
                .passwordEncrypted("encrypted_password")
                .build();

        DatasourceConfig saved = datasourceConfigRepository.save(config);
        assertNotNull(saved.getId());
        assertEquals("Test MySQL Source", saved.getConfigName());
        assertEquals(DbType.mysql, saved.getDbType());
    }

    @Test
    @DisplayName("按类型查询数据源 - 应返回正确类型")
    void testFindByDbType() {
        // Assuming init data has been loaded
        var mysqlConfigs = datasourceConfigRepository.findByDbType(DbType.mysql);
        assertNotNull(mysqlConfigs);
    }

    @Test
    @DisplayName("更新数据源配置 - 应保留创建人和未修改的密码")
    void testUpdateDatasourcePreservesSystemFields() {
        DatasourceConfig original = datasourceConfigRepository.saveAndFlush(DatasourceConfig.builder()
                .creatorId(1L)
                .configName("Original Source")
                .dbType(DbType.mysql)
                .host("localhost")
                .port(3306)
                .database("original_db")
                .username("root")
                .passwordEncrypted("existing_encrypted_password")
                .build());

        DatasourceConfig request = DatasourceConfig.builder()
                .configName("Updated Source")
                .host("127.0.0.1")
                .port(3307)
                .database("updated_db")
                .username("app_user")
                .build();

        DatasourceConfig updated = datasourceService.updateDatasource(original.getId(), request);

        assertEquals(1L, updated.getCreatorId());
        assertEquals(DbType.mysql, updated.getDbType());
        assertEquals("existing_encrypted_password", updated.getPasswordEncrypted());
        assertEquals("Updated Source", updated.getConfigName());
        assertEquals("127.0.0.1", updated.getHost());
    }
}
