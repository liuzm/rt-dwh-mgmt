package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PostgresCdcService {
    private final EncryptionUtil encryptionUtil;
    private final ObjectMapper objectMapper;

    public Map<String, Object> preflight(DatasourceConfig source, SyncTask task) {
        requirePostgres(source);
        try (Connection connection = connect(source)) {
            String walLevel = scalar(connection, "SELECT current_setting('wal_level')");
            int maxSlots = Integer.parseInt(scalar(connection, "SELECT current_setting('max_replication_slots')"));
            int usedSlots = Integer.parseInt(scalar(connection, "SELECT count(*)::text FROM pg_replication_slots"));
            boolean replicationRole = Boolean.parseBoolean(scalar(connection,
                    "SELECT (rolreplication OR rolsuper)::text FROM pg_roles WHERE rolname = current_user"));
            boolean canCreate = Boolean.parseBoolean(scalar(connection,
                    "SELECT has_database_privilege(current_user, current_database(), 'CREATE')::text"));
            List<Resource> resources = resources(task);
            int existing = 0;
            for (Resource resource : resources) if (slotExists(connection, resource.slot())) existing++;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ready", "logical".equalsIgnoreCase(walLevel) && replicationRole
                    && canCreate && maxSlots - usedSlots >= resources.size() - existing);
            result.put("walLevel", walLevel);
            result.put("replicationRole", replicationRole);
            result.put("canCreatePublication", canCreate);
            result.put("maxReplicationSlots", maxSlots);
            result.put("usedReplicationSlots", usedSlots);
            result.put("requiredNewSlots", resources.size() - existing);
            result.put("resources", resources);
            if (!"logical".equalsIgnoreCase(walLevel)) result.put("error", "PostgreSQL wal_level 必须为 logical");
            else if (!replicationRole) result.put("error", "当前账号缺少 REPLICATION 权限");
            else if (!canCreate) result.put("error", "当前账号缺少创建 Publication 的数据库权限");
            else if (maxSlots - usedSlots < resources.size() - existing) result.put("error", "可用 replication slot 数量不足");
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("PostgreSQL CDC 预检失败: " + exception.getMessage(), exception);
        }
    }

    public void assertReady(DatasourceConfig source, SyncTask task) {
        Map<String, Object> status = preflight(source, task);
        if (!Boolean.TRUE.equals(status.get("ready"))) {
            throw new IllegalStateException(String.valueOf(status.getOrDefault("error", "PostgreSQL CDC 环境未就绪")));
        }
    }

    public Map<String, Object> cleanup(DatasourceConfig source, SyncTask task) {
        requirePostgres(source);
        List<String> removedSlots = new ArrayList<>();
        List<String> removedPublications = new ArrayList<>();
        try (Connection connection = connect(source)) {
            for (Resource resource : resources(task)) {
                if (slotActive(connection, resource.slot())) {
                    throw new IllegalStateException("Replication slot 正在使用，需先确认 Flink Job 已停止: " + resource.slot());
                }
                if (slotExists(connection, resource.slot())) {
                    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_drop_replication_slot(?)")) {
                        statement.setString(1, resource.slot());
                        statement.execute();
                    }
                    removedSlots.add(resource.slot());
                }
                if (publicationExists(connection, resource.publication())) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP PUBLICATION IF EXISTS \"" + resource.publication() + "\"");
                    }
                    removedPublications.add(resource.publication());
                }
            }
            return Map.of("removedSlots", removedSlots, "removedPublications", removedPublications);
        } catch (Exception exception) {
            throw new IllegalStateException("清理 PostgreSQL CDC 资源失败: " + exception.getMessage(), exception);
        }
    }

    List<Resource> resources(SyncTask task) {
        try {
            JsonNode mappings = objectMapper.readTree(task.getTableMappings());
            List<Resource> resources = new ArrayList<>();
            for (JsonNode mapping : mappings) {
                String table = mapping.path("sourceTable").asText();
                if (!table.isBlank()) resources.add(new Resource(table,
                        PostgresCdcNaming.slot(task, table), PostgresCdcNaming.publication(task, table)));
            }
            if (resources.isEmpty()) throw new IllegalArgumentException("任务没有 PostgreSQL 表映射");
            return resources;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解析 PostgreSQL CDC 表映射: " + exception.getMessage(), exception);
        }
    }

    private Connection connect(DatasourceConfig source) throws Exception {
        String url = "jdbc:postgresql://" + source.getHost() + ":" + source.getPort() + "/" + source.getDatabase();
        return DriverManager.getConnection(url, source.getUsername(), encryptionUtil.decrypt(source.getPasswordEncrypted()));
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("预检查询未返回结果");
            return result.getString(1);
        }
    }

    private boolean slotExists(Connection connection, String slot) throws Exception {
        return exists(connection, "SELECT 1 FROM pg_replication_slots WHERE slot_name = ?", slot);
    }

    private boolean slotActive(Connection connection, String slot) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active FROM pg_replication_slots WHERE slot_name = ?")) {
            statement.setString(1, slot);
            try (ResultSet result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private boolean publicationExists(Connection connection, String publication) throws Exception {
        return exists(connection, "SELECT 1 FROM pg_publication WHERE pubname = ?", publication);
    }

    private boolean exists(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void requirePostgres(DatasourceConfig source) {
        if (source == null || source.getDbType() != DatasourceConfig.DbType.postgresql) {
            throw new IllegalArgumentException("仅 PostgreSQL 数据源支持 CDC Slot 管理");
        }
    }

    public record Resource(String sourceTable, String slot, String publication) {}
}
