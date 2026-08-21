package com.rtdwh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private final DataSource dataSource; // Management DB DataSource (auto-injected by Spring)
    private final FlinkClusterService flinkClusterService;

    @Value("${paimon.jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.warehouse-path}")
    private String paimonWarehousePath;

    /**
     * Check Flink cluster health (delegated to FlinkClusterService).
     */
    public Map<String, Object> checkFlink() {
        return flinkClusterService.healthCheck();
    }

    /**
     * Check Paimon metastore connectivity.
     * Verifies the Paimon JDBC metastore MySQL is reachable.
     */
    public Map<String, Object> checkPaimon() {
        long startTime = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(paimonJdbcUri, paimonJdbcUser, paimonJdbcPassword)) {
            // Simple ping: execute a lightweight query
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT 1")) {
                rs.next(); // consume result
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // Try to get some basic info from Paimon metastore
            int dbCount = 0;
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) dbCount++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "healthy");
            result.put("warehousePath", paimonWarehousePath);
            result.put("metastoreUri", paimonJdbcUri);
            result.put("responseTimeMs", durationMs);
            result.put("databaseCount", dbCount);
            result.put("readOnly", conn.isReadOnly());
            result.put("checkedAt", Instant.now().toString());
            return result;
        } catch (Exception e) {
            log.warn("Paimon health check failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("metastoreUri", paimonJdbcUri);
            result.put("warehousePath", paimonWarehousePath);
            result.put("responseTimeMs", System.currentTimeMillis() - startTime);
            result.put("checkedAt", Instant.now().toString());
            if (isUnknownDatabase(e)) {
                result.put("status", "not_initialized");
                result.put("diagnosticCode", "DATABASE_NOT_FOUND");
                result.put("error", "Paimon 元数据库 rtdwh_paimon_meta 尚未创建");
                result.put("suggestion", "在 MySQL 中执行 CREATE DATABASE IF NOT EXISTS rtdwh_paimon_meta CHARACTER SET utf8mb3 COLLATE utf8mb3_unicode_ci");
            } else {
                result.put("status", "unreachable");
                result.put("error", safeMessage(e));
            }
            return result;
        }
    }

    /**
     * Check MySQL management DB connectivity.
     * Uses the already-configured Spring DataSource (Druid pool).
     */
    public Map<String, Object> checkMySQL() {
        long startTime = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            // Ping with validation query
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // Get basic DB info
            String dbName = conn.getCatalog();
            var meta = conn.getMetaData();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "healthy");
            result.put("database", dbName != null ? dbName : "unknown");
            result.put("responseTimeMs", durationMs);
            result.put("driver", meta.getDriverName());
            result.put("dbProduct", meta.getDatabaseProductName());
            result.put("dbVersion", meta.getDatabaseProductVersion());
            result.put("readOnly", conn.isReadOnly());
            result.put("checkedAt", Instant.now().toString());
            return result;
        } catch (Exception e) {
            log.warn("MySQL health check failed: {}", e.getMessage());
            return Map.of(
                "status", "unhealthy",
                "error", safeMessage(e),
                "responseTimeMs", System.currentTimeMillis() - startTime,
                "checkedAt", Instant.now().toString()
            );
        }
    }

    /** Run independent checks concurrently so one slow dependency does not delay all others serially. */
    public Map<String, Object> checkAll() {
        long startTime = System.currentTimeMillis();
        CompletableFuture<Map<String, Object>> flinkFuture = safeFuture("Flink", this::checkFlink);
        CompletableFuture<Map<String, Object>> paimonFuture = safeFuture("Paimon", this::checkPaimon);
        CompletableFuture<Map<String, Object>> mysqlFuture = safeFuture("MySQL", this::checkMySQL);

        Map<String, Object> flink = flinkFuture.join();
        Map<String, Object> paimon = paimonFuture.join();
        Map<String, Object> mysql = mysqlFuture.join();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flink", flink);
        result.put("paimon", paimon);
        result.put("mysql", mysql);
        result.put("overall", determineOverallStatus(flink, paimon, mysql));
        result.put("checkedAt", Instant.now().toString());
        result.put("durationMs", System.currentTimeMillis() - startTime);
        return result;
    }

    public Map<String, Object> checkComponent(String component) {
        return switch (component.toLowerCase(Locale.ROOT)) {
            case "flink" -> checkFlink();
            case "paimon" -> checkPaimon();
            case "mysql" -> checkMySQL();
            default -> throw new IllegalArgumentException("不支持的检查组件: " + component);
        };
    }

    /**
     * Determine overall system health status.
     */
    public String determineOverallStatus(Map<String, Object> flink, Map<String, Object> paimon, Map<String, Object> mysql) {
        String flinkStatus = (String) flink.getOrDefault("status", "unknown");
        String paimonStatus = (String) paimon.getOrDefault("status", "unknown");
        String mysqlStatus = (String) mysql.getOrDefault("status", "unknown");

        // The management database is required for the control plane itself.
        if (!"healthy".equals(mysqlStatus)) {
            return "unhealthy";
        }
        // Flink or Paimon failures degrade data-plane capabilities but do not take down the UI.
        if (!"healthy".equals(flinkStatus) || !"healthy".equals(paimonStatus)) {
            return "degraded";
        }
        return "healthy";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private boolean isUnknownDatabase(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1049
                    || "42000".equals(sqlException.getSQLState()))) {
                String message = sqlException.getMessage();
                if (message != null && message.toLowerCase(Locale.ROOT).contains("unknown database")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private CompletableFuture<Map<String, Object>> safeFuture(
            String component,
            java.util.function.Supplier<Map<String, Object>> check
    ) {
        return CompletableFuture.supplyAsync(check).exceptionally(exception -> {
            log.error("Unexpected {} health-check failure", component, exception);
            return Map.of(
                    "status", "unhealthy",
                    "error", "检查过程发生内部异常: " + safeThrowableMessage(exception),
                    "checkedAt", Instant.now().toString()
            );
        });
    }

    private String safeThrowableMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
