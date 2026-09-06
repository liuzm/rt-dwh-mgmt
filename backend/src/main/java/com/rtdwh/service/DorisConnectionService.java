package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DorisConnectionService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Value("${doris.enabled:true}")
    private boolean enabled;

    @Value("${doris.jdbc-url:jdbc:mysql://localhost:9030}")
    private String jdbcUrl;

    @Value("${doris.username:root}")
    private String username;

    @Value("${doris.password:}")
    private String password;

    @Value("${doris.catalog:rtdwh_paimon}")
    private String catalog;

    @Value("${doris.database:ods}")
    private String database;

    @Value("${doris.http-url:http://localhost:8030}")
    private String httpUrl;

    @Value("${doris.pool.maximum-size:10}")
    private int maximumPoolSize;

    @Value("${doris.pool.connection-timeout-ms:5000}")
    private long connectionTimeoutMs;

    @Value("${doris.workload-group:rtdwh_adhoc}")
    private String workloadGroup;

    @Value("${doris.exec-mem-limit-bytes:2147483648}")
    private long execMemLimitBytes;

    private volatile HikariDataSource dataSource;

    @PostConstruct
    void initialize() {
        rebuildPool();
    }

    @PreDestroy
    void close() {
        HikariDataSource current = dataSource;
        if (current != null) current.close();
    }

    public Connection getConnection() throws java.sql.SQLException {
        if (!enabled) throw new IllegalStateException("Doris 即席查询引擎未启用");
        HikariDataSource current = dataSource;
        if (current == null) throw new IllegalStateException("Doris 连接池尚未初始化");
        return current.getConnection();
    }

    public synchronized void updateRuntimeConfig(
            boolean enabled,
            String jdbcUrl,
            String username,
            String password,
            String httpUrl,
            String catalog,
            String database
    ) {
        this.enabled = enabled;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password == null ? "" : password;
        this.httpUrl = httpUrl;
        this.catalog = catalog;
        this.database = database;
        rebuildPool();
    }

    public Map<String, Object> testConnection(
            String jdbcUrl,
            String username,
            String password,
            String catalog,
            String database
    ) {
        long started = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            String version;
            try (ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
                resultSet.next();
                version = resultSet.getString(1);
            }
            statement.execute("SWITCH " + quoteIdentifier(catalog));
            statement.execute("USE " + quoteIdentifier(database));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "healthy");
            result.put("endpoint", jdbcUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("dorisVersion", version);
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            return result;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unreachable");
            result.put("endpoint", jdbcUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("error", safeMessage(exception));
            result.put("suggestion", "确认 Doris FE 9030 端口可用、查询账号有 Catalog 权限，并检查 Paimon Catalog 是否已创建");
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            return result;
        }
    }

    public Map<String, Object> healthCheck() {
        long started = System.currentTimeMillis();
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            String version;
            try (ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
                resultSet.next();
                version = resultSet.getString(1);
            }
            int aliveBackends = -1;
            try {
                aliveBackends = countAlive(statement, "SHOW BACKENDS");
            } catch (Exception permissionOrVersionError) {
                log.debug("Doris backend details are not visible to query user: {}",
                        permissionOrVersionError.getMessage());
            }
            statement.execute("SWITCH " + quoteIdentifier(catalog));
            try (ResultSet ignored = statement.executeQuery("SHOW DATABASES")) {
                // Opening the result verifies that the configured external Catalog is readable.
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", aliveBackends == 0 ? "degraded" : "healthy");
            result.put("endpoint", jdbcUrl);
            result.put("httpEndpoint", httpUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("dorisVersion", version);
            result.put("aliveBackends", aliveBackends);
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            if (aliveBackends == 0) result.put("error", "Doris FE 可连接，但没有可用 BE 节点");
            return result;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", enabled ? "unreachable" : "disabled");
            result.put("endpoint", jdbcUrl);
            result.put("catalog", catalog);
            result.put("database", database);
            result.put("error", enabled ? safeMessage(exception) : "Doris 即席查询引擎未启用");
            result.put("responseTimeMs", System.currentTimeMillis() - started);
            result.put("checkedAt", Instant.now().toString());
            return result;
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getCatalog() { return catalog; }
    public String getDatabase() { return database; }
    public String getHttpUrl() { return httpUrl; }
    public String getWorkloadGroup() { return workloadGroup; }
    public long getExecMemLimitBytes() { return execMemLimitBytes; }

    /**
     * Reads the native Doris runtime statistics associated with the session trace id.
     * The endpoint is available in Doris 4.0+; older clusters simply return no sample.
     */
    public QueryRuntimeStats getQueryRuntimeStats(String traceId) {
        try {
            JsonNode response = getJson("/rest/v2/manager/query/statistics/" + encode(traceId));
            if (response.path("code").asInt(-1) != 0 || !response.path("data").isObject()) return null;
            JsonNode data = response.path("data");
            return new QueryRuntimeStats(
                    null,
                    number(data, "scanRows"),
                    number(data, "scanBytes"),
                    number(data, "cpuMs"),
                    number(data, "maxPeakMemoryBytes"),
                    number(data, "scanBytesFromLocalStorage"),
                    number(data, "scanBytesFromRemoteStorage"),
                    number(data, "bytesWriteIntoCache"),
                    data.path("progress").asText(null)
            );
        } catch (Exception unsupportedOrUnavailable) {
            log.debug("Doris runtime statistics are not available for trace {}: {}",
                    traceId, unsupportedOrUnavailable.getMessage());
            return null;
        }
    }

    public String getQueryIdByTraceId(String traceId) {
        try {
            JsonNode response = getJson("/rest/v2/manager/query/trace_id/" + encode(traceId));
            if (response.path("code").asInt(-1) != 0 || !response.path("data").isTextual()) return null;
            String queryId = response.path("data").asText();
            return queryId.isBlank() ? null : queryId;
        } catch (Exception unsupportedOrUnavailable) {
            log.debug("Doris query id is not available for trace {}: {}", traceId, unsupportedOrUnavailable.getMessage());
            return null;
        }
    }

    public String getQueryProfile(String queryId) {
        if (queryId == null || !queryId.matches("[A-Fa-f0-9-]{8,128}")) {
            throw new IllegalArgumentException("Doris Query ID 格式不正确");
        }
        try {
            JsonNode response = getJson("/rest/v2/manager/query/profile/text/" + encode(queryId));
            if (response.path("code").asInt(-1) != 0) {
                throw new IllegalStateException(response.path("data").asText("Profile 不可用"));
            }
            String profile = response.path("data").path("profile").asText("");
            if (profile.isBlank()) throw new IllegalStateException("Doris 未保留该查询的 Profile");
            return profile.length() > 2_000_000 ? profile.substring(0, 2_000_000) + "\n... [已截断]" : profile;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Doris Query Profile 失败: " + exception.getMessage(), exception);
        }
    }

    private JsonNode getJson(String path) throws Exception {
        String base = httpUrl.endsWith("/") ? httpUrl.substring(0, httpUrl.length() - 1) : httpUrl;
        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.asLong();
        if (value.isTextual()) {
            try { return Long.parseLong(value.asText()); } catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    public record QueryRuntimeStats(
            String queryId,
            long scannedRows,
            long scannedBytes,
            long cpuMs,
            long peakMemoryBytes,
            long localScanBytes,
            long remoteScanBytes,
            long cacheWriteBytes,
            String progress
    ) {
        QueryRuntimeStats merge(QueryRuntimeStats newer) {
            if (newer == null) return this;
            return new QueryRuntimeStats(
                    newer.queryId != null ? newer.queryId : queryId,
                    Math.max(scannedRows, newer.scannedRows),
                    Math.max(scannedBytes, newer.scannedBytes),
                    Math.max(cpuMs, newer.cpuMs),
                    Math.max(peakMemoryBytes, newer.peakMemoryBytes),
                    Math.max(localScanBytes, newer.localScanBytes),
                    Math.max(remoteScanBytes, newer.remoteScanBytes),
                    Math.max(cacheWriteBytes, newer.cacheWriteBytes),
                    newer.progress != null ? newer.progress : progress
            );
        }
    }

    private synchronized void rebuildPool() {
        HikariDataSource previous = dataSource;
        if (!enabled) {
            dataSource = null;
            if (previous != null) previous.close();
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("rtdwh-doris-query");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(Math.max(1, maximumPoolSize));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Math.max(1000L, connectionTimeoutMs));
        config.setInitializationFailTimeout(-1L);
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("characterEncoding", "UTF-8");
        config.addDataSourceProperty("useSSL", "false");
        config.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        dataSource = new HikariDataSource(config);
        if (previous != null) previous.close();
        log.info("Doris query pool configured: endpoint={}, catalog={}, database={}", jdbcUrl, catalog, database);
    }

    private int countAlive(Statement statement, String sql) throws Exception {
        int alive = 0;
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int aliveColumn = -1;
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if ("alive".equalsIgnoreCase(metadata.getColumnLabel(index))) aliveColumn = index;
            }
            while (resultSet.next()) {
                if (aliveColumn < 0 || truthy(resultSet.getObject(aliveColumn))) alive++;
            }
        }
        return alive;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && Set.of("true", "yes", "1").contains(String.valueOf(value).toLowerCase(Locale.ROOT));
    }

    public static String quoteIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法 Doris 标识符: " + identifier);
        }
        return "`" + identifier + "`";
    }

    public static String quoteLiteral(String value) {
        if (value == null) throw new IllegalArgumentException("Doris 字符串参数不能为空");
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
