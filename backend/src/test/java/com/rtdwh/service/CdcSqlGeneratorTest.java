package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.util.EncryptionUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdcSqlGeneratorTest {

    @Test
    void combinesMultipleTableMappingsIntoOneStatementSetJob() {
        CdcTableIntrospector introspector = mock(CdcTableIntrospector.class);
        EncryptionUtil encryptionUtil = mock(EncryptionUtil.class);
        CdcSqlGenerator generator = new CdcSqlGenerator(introspector, encryptionUtil);
        ReflectionTestUtils.setField(generator, "defaultStartMode", "initial");
        ReflectionTestUtils.setField(generator, "warehousePath", "file:///tmp/paimon");
        ReflectionTestUtils.setField(generator, "paimonMetastore", "jdbc");
        ReflectionTestUtils.setField(generator, "paimonJdbcUri", "jdbc:mysql://mysql:3306/paimon");
        ReflectionTestUtils.setField(generator, "paimonJdbcUser", "paimon");
        ReflectionTestUtils.setField(generator, "paimonJdbcPassword", "secret");
        ReflectionTestUtils.setField(generator, "paimonCatalogKey", "rtdwh");

        when(encryptionUtil.decrypt(anyString())).thenReturn("source-secret");
        when(introspector.introspectTable(any(), anyString())).thenAnswer(invocation ->
                new CdcTableIntrospector.TableSchema(
                        invocation.getArgument(1),
                        List.of(new CdcTableIntrospector.ColumnSchema("id", "BIGINT", "", false, true)),
                        List.of("id"),
                        "UTC"));

        DatasourceConfig source = DatasourceConfig.builder()
                .configName("mysql")
                .dbType(DatasourceConfig.DbType.mysql)
                .host("mysql")
                .port(3306)
                .database("source_db")
                .username("root")
                .passwordEncrypted("encrypted")
                .build();
        SyncTask task = SyncTask.builder()
                .id(7L)
                .taskName("multi_table_cdc")
                .taskType(SyncTask.TaskType.cdc_sync)
                .syncStrategy(SyncTask.SyncStrategy.full_then_incremental)
                .tableMappings("""
                        [
                          {"sourceTable":"orders","targetDb":"ods","targetTable":"ods_orders"},
                          {"sourceTable":"users","targetDb":"ods","targetTable":"ods_users"}
                        ]
                        """)
                .build();

        String template = generator.generateReleaseSql(task, source);
        assertTrue(!template.contains("source-secret"));
        assertTrue(!template.contains("'secret'"));
        assertTrue(template.contains("__RTDWH_SOURCE_CREDENTIAL__"));
        when(encryptionUtil.decrypt(anyString())).thenReturn("rotated'password");
        String resolved = generator.bindReleaseCredentials(template, source);
        assertTrue(resolved.contains("rotated''password"));
        assertTrue(!resolved.contains("__RTDWH_SOURCE_CREDENTIAL__"));
        String sql = generator.generateCdcSql(task, source);
        List<String> statements = FlinkClusterService.splitSqlStatements(sql);

        assertTrue(sql.contains("EXECUTE STATEMENT SET"));
        assertTrue(sql.contains("INSERT INTO `ods`.`ods_orders`"));
        assertTrue(sql.contains("INSERT INTO `ods`.`ods_users`"));
        assertEquals(1, statements.stream()
                .filter(statement -> statement.startsWith("EXECUTE STATEMENT SET"))
                .count());
    }
}
