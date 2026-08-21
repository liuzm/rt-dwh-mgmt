package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcSqlGenerator {

    private final CdcTableIntrospector introspector;
    private final EncryptionUtil encryptionUtil;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Value("${cdc.flink-cdc-version:3.3.0}")
    private String flinkCdcVersion;

    @Value("${cdc.debezium-version:2.5.0}")
    private String debeziumVersion;

    @Value("${cdc.default-start-mode:initial}")
    private String defaultStartMode;

    @Value("${paimon.warehouse-path}")
    private String warehousePath;

    @Value("${paimon.metastore:jdbc}")
    private String paimonMetastore;

    @Value("${paimon.jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.catalog-key:rtdwh}")
    private String paimonCatalogKey;

    /**
     * Generate CDC SQL for a sync task based on its table mappings.
     */
    public String generateCdcSql(SyncTask task, DatasourceConfig sourceConfig, DatasourceConfig targetConfig) {
        List<Map<String, String>> mappings = parseTableMappings(task.getTableMappings());
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("表映射配置为空");
        }
        if (sourceConfig == null || targetConfig == null) {
            throw new IllegalArgumentException("源/目标数据源配置不存在");
        }
        if (targetConfig.getDbType() != DbType.paimon) {
            throw new IllegalArgumentException("CDC 目标数据源必须是 Paimon");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("-- Flink CDC 同步任务: ").append(task.getTaskName()).append("\n");
        sql.append("-- 源: ").append(sourceConfig.getConfigName()).append(" -> 目标: ").append(targetConfig.getConfigName()).append("\n");
        sql.append("-- 策略: ").append(task.getSyncStrategy() != null ? task.getSyncStrategy() : defaultStartMode).append("\n\n");
        sql.append(generatePaimonCatalogSql());
        sql.append("\n");

        // Generate SQL for each table mapping
        for (int i = 0; i < mappings.size(); i++) {
            Map<String, String> mapping = mappings.get(i);
            String sourceTable = mapping.get("sourceTable");
            String targetDb = mapping.get("targetDb");
            String targetTable = mapping.get("targetTable");

            validateIdentifier(sourceTable, "源表名");
            validateIdentifier(targetDb, "目标库名");
            validateIdentifier(targetTable, "目标表名");

            if (i > 0) sql.append("\n");
            String syncMode = mapping.getOrDefault("syncMode", "full+incremental");
            String startMode = "incremental".equalsIgnoreCase(syncMode)
                    || task.getSyncStrategy() == SyncTask.SyncStrategy.incremental_only
                    ? "latest-offset" : defaultStartMode;
            sql.append(generateTableCdcSql(sourceConfig, sourceTable, targetConfig, targetDb, targetTable, task, startMode));
        }

        return sql.toString();
    }

    /**
     * Generate CDC SQL for a single table mapping.
     */
    private String generateTableCdcSql(DatasourceConfig sourceConfig, String sourceTable,
                                        DatasourceConfig targetConfig, String targetDb,
                                        String targetTable, SyncTask task, String startMode) {
        StringBuilder sql = new StringBuilder();

        sql.append("CREATE DATABASE IF NOT EXISTS ").append(identifier(targetDb)).append(";\n\n");

        // 1. Create CDC source table
        // Keep connector tables session-scoped. After switching to the Paimon catalog,
        // a persistent CREATE TABLE would otherwise be delegated to that catalog.
        sql.append("CREATE TEMPORARY TABLE ").append(identifier("source_" + sourceTable)).append(" (\n");

        // Introspect source table structure
        CdcTableIntrospector.TableSchema schema;
        try {
            schema = introspector.introspectTable(sourceConfig, sourceTable);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取源表结构 " + sourceTable + ": " + e.getMessage(), e);
        }

        // Generate column definitions
        List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < schema.columns().size(); i++) {
            CdcTableIntrospector.ColumnSchema col = schema.columns().get(i);
            String flinkType = toFlinkType(col.type());
            sql.append("  ").append(identifier(col.name())).append(" ").append(flinkType);
            if (i < schema.columns().size() - 1) sql.append(",");
            sql.append("\n");
            if (col.primaryKey()) pkCols.add(col.name());
        }
        if (schema.columns().isEmpty()) {
            throw new IllegalArgumentException("源表没有可同步的字段: " + sourceTable);
        }
        if (!pkCols.isEmpty()) {
            sql.append("  ,PRIMARY KEY (").append(pkCols.stream().map(this::identifier).collect(java.util.stream.Collectors.joining(", "))).append(") NOT ENFORCED\n");
        }

        // Add watermark for event time
        sql.append(") WITH (\n");
        sql.append(generateSourceWithClause(
                sourceConfig, sourceTable, startMode, schema.serverTimeZone()));
        sql.append(");\n\n");

        // 2. Create Paimon target table
        sql.append("CREATE TABLE IF NOT EXISTS ").append(identifier(targetDb)).append(".").append(identifier(targetTable)).append(" (\n");

        // Mirror source columns to target
        for (int i = 0; i < schema.columns().size(); i++) {
            CdcTableIntrospector.ColumnSchema col = schema.columns().get(i);
            String flinkType = toFlinkType(col.type());
            sql.append("  ").append(identifier(col.name())).append(" ").append(flinkType);
            if (i < schema.columns().size() - 1) sql.append(",");
            sql.append("\n");
        }

        // Add primary key for Paimon
        if (!pkCols.isEmpty()) {
            sql.append("  ,PRIMARY KEY (").append(pkCols.stream().map(this::identifier).collect(java.util.stream.Collectors.joining(", "))).append(") NOT ENFORCED\n");
        }

        sql.append(") WITH (\n");
        sql.append(generateSinkWithClause(targetDb, targetTable, task));
        sql.append(");\n\n");

        // 3. INSERT INTO statement
        List<String> columnNames = schema.columns().stream().map(c -> identifier(c.name())).toList();
        sql.append("INSERT INTO ").append(identifier(targetDb)).append(".").append(identifier(targetTable)).append(" (\n");
        sql.append("  ").append(String.join(",\n  ", columnNames));
        sql.append("\n) SELECT\n");
        sql.append("  ").append(String.join(",\n  ", columnNames));
        sql.append("\nFROM ").append(identifier("source_" + sourceTable)).append(";\n");

        return sql.toString();
    }

    /**
     * Generate source table WITH clause for CDC connector.
     */
    private String generateSourceWithClause(DatasourceConfig sourceConfig, String sourceTable,
                                            String startMode, String serverTimeZone) {
        StringBuilder sb = new StringBuilder();
        DbType dbType = sourceConfig.getDbType();

        if (dbType == DbType.mysql) {
            sb.append("  'connector' = 'mysql-cdc',\n");
            sb.append("  'hostname' = '").append(escape(sourceConfig.getHost())).append("',\n");
            sb.append("  'port' = '").append(sourceConfig.getPort()).append("',\n");
            sb.append("  'username' = '").append(escape(sourceConfig.getUsername())).append("',\n");
            sb.append("  'password' = '").append(escape(decryptPassword(sourceConfig.getPasswordEncrypted()))).append("',\n");
            sb.append("  'database-name' = '").append(escape(sourceConfig.getDatabase())).append("',\n");
            sb.append("  'table-name' = '").append(escape(sourceTable)).append("',\n");
            if (serverTimeZone == null || serverTimeZone.isBlank()) {
                throw new IllegalStateException("无法检测 MySQL 服务端时区");
            }
            sb.append("  'server-time-zone' = '").append(escape(serverTimeZone)).append("',\n");
            sb.append("  'scan.startup.mode' = '").append(escape(startMode)).append("',\n");
            sb.append("  'debezium.snapshot.lock.mode' = 'none'\n");
        } else if (dbType == DbType.postgresql) {
            sb.append("  'connector' = 'postgres-cdc',\n");
            sb.append("  'hostname' = '").append(escape(sourceConfig.getHost())).append("',\n");
            sb.append("  'port' = '").append(sourceConfig.getPort()).append("',\n");
            sb.append("  'username' = '").append(escape(sourceConfig.getUsername())).append("',\n");
            sb.append("  'password' = '").append(escape(decryptPassword(sourceConfig.getPasswordEncrypted()))).append("',\n");
            sb.append("  'database-name' = '").append(escape(sourceConfig.getDatabase())).append("',\n");
            sb.append("  'schema-name' = 'public',\n");
            sb.append("  'table-name' = '").append(escape(sourceTable)).append("',\n");
            sb.append("  'scan.startup.mode' = '").append(escape(startMode)).append("',\n");
            sb.append("  'decoding.plugin.name' = 'pgoutput'\n");
        } else {
            throw new IllegalArgumentException("Unsupported source database type: " + dbType);
        }

        return sb.toString();
    }

    /**
     * Generate sink table WITH clause for Paimon connector.
     */
    private String generateSinkWithClause(String targetDb, String targetTable, SyncTask task) {
        StringBuilder sb = new StringBuilder();
        // The table is created inside the Paimon catalog, so connector/path and
        // metastore options belong to CREATE CATALOG rather than each table.
        sb.append("  'changelog-producer' = 'input'\n");

        return sb.toString();
    }

    private String generatePaimonCatalogSql() {
        String catalogName = paimonCatalogKey == null || paimonCatalogKey.isBlank()
                ? "rtdwh" : paimonCatalogKey.trim();
        return "CREATE CATALOG " + identifier(catalogName) + " WITH (\n"
                + "  'type' = 'paimon',\n"
                + "  'metastore' = '" + escape(paimonMetastore) + "',\n"
                + "  'uri' = '" + escape(paimonJdbcUri) + "',\n"
                + "  'jdbc.user' = '" + escape(paimonJdbcUser) + "',\n"
                + "  'jdbc.password' = '" + escape(paimonJdbcPassword) + "',\n"
                + "  'catalog-key' = '" + escape(catalogName) + "',\n"
                + "  'warehouse' = '" + escape(warehousePath) + "'\n"
                + ");\n\n"
                + "USE CATALOG " + identifier(catalogName) + ";\n";
    }

    /**
     * Convert JDBC type to Flink SQL type.
     */
    private String toFlinkType(String jdbcType) {
        String upper = jdbcType.toUpperCase();
        return switch (upper) {
            case "VARCHAR", "CHAR", "TEXT", "LONGTEXT" -> "STRING";
            case "INT", "INTEGER", "SMALLINT", "TINYINT" -> "INT";
            case "BIGINT" -> "BIGINT";
            case "DECIMAL", "NUMERIC" -> "DECIMAL(38, 18)";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME(0)";
            case "DATETIME", "TIMESTAMP", "TIMESTAMP(6)", "TIMESTAMP(3)" -> "TIMESTAMP(3)";
            case "YEAR" -> "INT";
            default -> "STRING";
        };
    }

    private String decryptPassword(String encryptedPassword) {
        try {
            return encryptionUtil.decrypt(encryptedPassword);
        } catch (Exception e) {
            log.warn("Failed to decrypt password: {}", e.getMessage());
            return encryptedPassword;
        }
    }

    /**
     * Parse table mappings from JSON string.
     */
    private List<Map<String, String>> parseTableMappings(String mappingsJson) {
        List<Map<String, String>> mappings = new ArrayList<>();
        if (mappingsJson == null || mappingsJson.isBlank()) return mappings;

        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(mappingsJson);
            if (root.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : root) {
                    Map<String, String> map = new LinkedHashMap<>();
                    String sourceTable = item.path("sourceTable").asText("").trim();
                    String targetTable = item.path("targetTable").asText("").trim();
                    if (sourceTable.isBlank() || targetTable.isBlank()) {
                        throw new IllegalArgumentException("表映射必须包含 sourceTable 和 targetTable");
                    }
                    map.put("sourceTable", sourceTable);
                    map.put("targetDb", item.path("targetDb").asText("ods"));
                    map.put("targetTable", targetTable);
                    String syncMode = item.path("syncMode").asText("full+incremental");
                    if (!"full+incremental".equals(syncMode) && !"incremental".equals(syncMode)) {
                        throw new IllegalArgumentException("syncMode 只能是 full+incremental 或 incremental");
                    }
                    map.put("syncMode", syncMode);
                    mappings.add(map);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("表映射 JSON 格式不正确: " + e.getMessage(), e);
        }
        return mappings;
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + "只能包含字母、数字和下划线，且不能以数字开头");
        }
    }

    private String identifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * Build a fallback schema when introspection fails.
     */
    private CdcTableIntrospector.TableSchema buildFallbackSchema(String tableName) {
        List<CdcTableIntrospector.ColumnSchema> columns = List.of(
                CdcTableIntrospector.ColumnSchema.builder().name("id").type("BIGINT").primaryKey(true).build(),
                CdcTableIntrospector.ColumnSchema.builder().name("created_at").type("TIMESTAMP(3)").build(),
                CdcTableIntrospector.ColumnSchema.builder().name("updated_at").type("TIMESTAMP(3)").build()
        );
        return new CdcTableIntrospector.TableSchema(tableName, columns, List.of("id"), "UTC");
    }
}
