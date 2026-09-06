package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcTableIntrospector {

    private final EncryptionUtil encryptionUtil;

    /**
     * Introspect all table names from a datasource.
     */
    public List<String> listTables(DatasourceConfig config) {
        String url = buildJdbcUrl(config);
        String decryptedPassword = encryptionUtil.decrypt(config.getPasswordEncrypted());

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), decryptedPassword)) {
            conn.setReadOnly(true);
            List<String> tables = new ArrayList<>();
            if (config.getDbType() == DatasourceConfig.DbType.mysql) {
                String sql = "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, config.getDatabase());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) tables.add(resultSet.getString(1));
                    }
                }
            } else if (config.getDbType() == DatasourceConfig.DbType.postgresql) {
                String sql = "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name";
                try (PreparedStatement statement = conn.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) tables.add(resultSet.getString(1));
                }
            } else {
                throw new IllegalArgumentException("仅 MySQL 和 PostgreSQL 数据源支持源表探测");
            }
            log.info("Discovered {} tables from datasource {} ({}/{})",
                    tables.size(), config.getId(), config.getConfigName(), config.getDatabase());
            return tables;
        } catch (SQLException e) {
            log.error("Failed to list tables for datasource {} at {}:{}/{}: {}",
                    config.getId(), config.getHost(), config.getPort(), config.getDatabase(), e.getMessage());
            throw new RuntimeException(String.format(
                    "获取表列表失败（%s:%d/%s）: %s",
                    config.getHost(), config.getPort(), config.getDatabase(), e.getMessage()));
        }
    }

    /**
     * Introspect a single table's column structure.
     */
    public TableSchema introspectTable(DatasourceConfig config, String tableName) {
        String url = buildJdbcUrl(config);
        String decryptedPassword = encryptionUtil.decrypt(config.getPasswordEncrypted());

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), decryptedPassword)) {
            DatabaseMetaData meta = conn.getMetaData();

            // Get primary keys
            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet pkRs = meta.getPrimaryKeys(config.getDatabase(), null, tableName)) {
                while (pkRs.next()) {
                    primaryKeys.add(pkRs.getString("COLUMN_NAME"));
                }
            }

            // Get columns
            List<ColumnSchema> columns = new ArrayList<>();
            try (ResultSet colRs = meta.getColumns(config.getDatabase(), null, tableName, "%")) {
                while (colRs.next()) {
                    String colName = colRs.getString("COLUMN_NAME");
                    String colType = colRs.getString("TYPE_NAME");
                    String comment = colRs.getString("REMARKS");
                    boolean isNullable = "YES".equals(colRs.getString("IS_NULLABLE"));

                    columns.add(new ColumnSchema(colName, colType, comment, isNullable, primaryKeys.contains(colName)));
                }
            }

            String serverTimeZone = config.getDbType() == DatasourceConfig.DbType.mysql
                    ? detectMySqlServerTimeZone(conn)
                    : null;
            return new TableSchema(tableName, columns, primaryKeys, serverTimeZone);
        } catch (SQLException e) {
            log.error("Failed to introspect table {} from datasource {}: {}", tableName, config.getId(), e.getMessage());
            throw new RuntimeException("获取表结构失败: " + e.getMessage());
        }
    }

    private String buildJdbcUrl(DatasourceConfig config) {
        return switch (config.getDbType()) {
            case mysql -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000",
                    config.getHost(), config.getPort(), config.getDatabase());
            case postgresql -> String.format(
                    "jdbc:postgresql://%s:%d/%s?connectTimeout=5&socketTimeout=10",
                    config.getHost(), config.getPort(), config.getDatabase());
            default -> throw new IllegalArgumentException("Unsupported datasource type: " + config.getDbType());
        };
    }

    private String detectMySqlServerTimeZone(Connection connection) throws SQLException {
        String sql = "SELECT @@session.time_zone, @@system_time_zone, "
                + "TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("MySQL 时区查询未返回结果");
            }
            return resolveMySqlServerTimeZone(
                    result.getString(1),
                    result.getString(2),
                    result.getInt(3));
        }
    }

    static String resolveMySqlServerTimeZone(String sessionTimeZone,
                                             String systemTimeZone,
                                             int offsetSeconds) {
        String session = normalizeZoneId(sessionTimeZone);
        if (session != null && !"SYSTEM".equalsIgnoreCase(session)) {
            return session;
        }
        String system = normalizeZoneId(systemTimeZone);
        if (system != null) {
            return system;
        }
        return offsetSeconds == 0 ? "UTC" : ZoneOffset.ofTotalSeconds(offsetSeconds).getId();
    }

    private static String normalizeZoneId(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim();
        if ("SYSTEM".equalsIgnoreCase(candidate)) return candidate;
        try {
            ZoneId.of(candidate);
            return candidate;
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    // ========================================================================
    // Inner classes for table schema
    // ========================================================================

    public record TableSchema(String tableName, List<ColumnSchema> columns,
                              List<String> primaryKeys, String serverTimeZone) {}

    public record ColumnSchema(
            String name,
            String type,
            String comment,
            boolean nullable,
            boolean primaryKey
    ) {
        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private String name;
            private String type;
            private String comment;
            private boolean nullable;
            private boolean primaryKey;

            public Builder name(String n) { name = n; return this; }
            public Builder type(String t) { type = t; return this; }
            public Builder comment(String c) { comment = c; return this; }
            public Builder nullable(boolean n) { nullable = n; return this; }
            public Builder primaryKey(boolean p) { primaryKey = p; return this; }
            public ColumnSchema build() { return new ColumnSchema(name, type, comment, nullable, primaryKey); }
        }
    }
}
