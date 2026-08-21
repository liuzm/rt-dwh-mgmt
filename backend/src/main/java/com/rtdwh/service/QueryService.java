package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.QueryExecuteDTO;
import com.rtdwh.entity.QueryHistory;
import com.rtdwh.entity.QueryHistory.QueryStatus;
import com.rtdwh.entity.QueryHistory.QueryType;
import com.rtdwh.repository.QueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {
    private static final Set<String> ALLOWED = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH");
    private static final Pattern WRITE = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|CALL|SET|USE)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METADATA = Pattern.compile("\\bpaimon_catalog_[a-z0-9_]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> FAILED_OPERATION_STATUSES = Set.of("ERROR", "FAILED", "CANCELED", "CLOSED");

    private final QueryHistoryRepository queryHistoryRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final FlinkClusterService flinkClusterService;

    @Value("${paimon.warehouse-path}") private String warehousePath;
    @Value("${paimon.jdbc-uri}") private String paimonJdbcUri;
    @Value("${paimon.jdbc-user}") private String paimonJdbcUser;
    @Value("${paimon.jdbc-password}") private String paimonJdbcPassword;
    @Value("${paimon.catalog-key}") private String paimonCatalogKey;
    @Value("${flink.sql-gateway.url}") private String sqlGatewayUrl;
    @Value("${flink.sql-gateway.enabled}") private boolean sqlGatewayEnabled;
    @Value("${query.max-rows}") private int defaultMaxRows;
    @Value("${query.max-export-rows}") private int maxExportRows;
    @Value("${query.timeout-seconds}") private int defaultTimeout;

    private final ConcurrentHashMap<Long, ActiveQuery> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> requests = new ConcurrentHashMap<>();

    private static final class ActiveQuery {
        final Long userId;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Statement statement;
        volatile String sessionHandle;
        volatile String operationHandle;
        volatile String flinkJobId;

        ActiveQuery(Long userId) {
            this.userId = userId;
        }

        void cancel() {
            cancelled.set(true);
            try {
                if (statement != null) statement.cancel();
            } catch (SQLException ignored) {
                // The JDBC fallback may already be closed.
            }
        }
    }

    public Map<String, Object> executeQuery(QueryExecuteDTO dto, Long userId) {
        return execute(dto, userId, QueryType.adhoc, defaultMaxRows);
    }

    public String exportToCsv(QueryExecuteDTO dto, Long userId) {
        Map<String, Object> result = execute(dto, userId, QueryType.adhoc, maxExportRows);
        if (!"success".equals(result.get("status"))) {
            throw new IllegalStateException("查询失败: " + result.get("errorMsg"));
        }
        @SuppressWarnings("unchecked") List<String> columns = (List<String>) result.get("columns");
        @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) result.get("rows");
        StringBuilder out = new StringBuilder("\uFEFF");
        out.append(columns.stream().map(this::csv).reduce((a, b) -> a + "," + b).orElse("")).append('\n');
        for (List<Object> row : rows) {
            out.append(row.stream().map(this::csv).reduce((a, b) -> a + "," + b).orElse("")).append('\n');
        }
        return out.toString();
    }

    private Map<String, Object> execute(QueryExecuteDTO dto, Long userId, QueryType type, int rowLimit) {
        String sql = validateSql(dto.getSql());
        if (!sqlGatewayEnabled && !sql.toUpperCase(Locale.ROOT).startsWith("SHOW ") && !METADATA.matcher(sql).find()) {
            throw new IllegalStateException("Flink SQL Gateway 未启用；当前仅允许查询 Paimon 元数据表");
        }

        int maxRows = Math.max(1, Math.min(dto.getMaxRows() == null ? defaultMaxRows : dto.getMaxRows(), rowLimit));
        int timeout = Math.max(1, Math.min(dto.getTimeoutSeconds() == null ? defaultTimeout : dto.getTimeoutSeconds(), 1800));
        String requestId = dto.getRequestId() == null || dto.getRequestId().isBlank()
                ? UUID.randomUUID().toString()
                : dto.getRequestId();
        QueryHistory history = queryHistoryRepository.save(QueryHistory.builder()
                .userId(userId)
                .sqlText(sql)
                .queryType(type)
                .status(QueryStatus.running)
                .build());
        long historyId = history.getId();
        ActiveQuery query = new ActiveQuery(userId);
        active.put(historyId, query);
        requests.put(requestId, historyId);

        long started = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        QueryStatus status = QueryStatus.success;
        String error = null;
        boolean truncated = false;
        try {
            truncated = sqlGatewayEnabled
                    ? executeViaSqlGateway(sql, maxRows, timeout, query, columns, rows)
                    : executeViaMetadataJdbc(sql, maxRows, timeout, query, columns, rows);
            if (query.cancelled.get()) status = QueryStatus.cancelled;
        } catch (Exception exception) {
            if (query.cancelled.get()
                    || exception instanceof CancellationException
                    || exception instanceof SQLException sqlException && "57014".equals(sqlException.getSQLState())) {
                status = QueryStatus.cancelled;
                error = "查询已取消";
            } else {
                status = QueryStatus.failed;
                error = conciseError(exception);
                log.error("Query failed: {}", error);
            }
            cancelGatewayOperation(query);
            cancelFlinkJob(query);
        } finally {
            closeGatewayResources(query);
            active.remove(historyId);
            requests.remove(requestId, historyId);
            query.statement = null;
        }

        long duration = System.currentTimeMillis() - started;
        history.setResultRowCount(rows.size());
        history.setDurationMs(duration);
        history.setStatus(status);
        history.setErrorMsg(error);
        queryHistoryRepository.save(history);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("rowCount", rows.size());
        result.put("durationMs", duration);
        result.put("status", status.name());
        result.put("errorMsg", error);
        result.put("historyId", historyId);
        result.put("requestId", requestId);
        result.put("truncated", truncated);
        return result;
    }

    private boolean executeViaSqlGateway(
            String sql,
            int maxRows,
            int timeoutSeconds,
            ActiveQuery query,
            List<String> columns,
            List<List<Object>> rows
    ) throws Exception {
        ensureQuerySlotAvailable();
        query.sessionHandle = openGatewaySession();
        executeGatewaySetupStatement(query, buildCatalogStatement(), timeoutSeconds);
        executeGatewaySetupStatement(query, "USE CATALOG paimon", timeoutSeconds);
        ensureNotCancelled(query);

        query.operationHandle = submitGatewayStatement(query.sessionHandle, sql);
        return fetchGatewayResults(query, maxRows, timeoutSeconds, columns, rows);
    }

    private void ensureQuerySlotAvailable() {
        Map<String, Object> health = flinkClusterService.healthCheck();
        if (!"healthy".equals(health.get("status"))) return;
        int totalSlots = intValue(health.get("taskSlotsTotal"));
        int availableSlots = intValue(health.get("taskSlotsAvailable"));
        int runningJobs = intValue(health.get("runningJobs"));
        if (totalSlots > 0 && availableSlots <= 0) {
            throw new IllegalStateException("Flink 无可用 Slot（" + availableSlots + "/" + totalSlots
                    + "，运行中 Job " + runningJobs
                    + " 个），即席查询无法调度。请增加 TaskManager Slot 或停止占用 Slot 的任务后重试");
        }
    }

    private String openGatewaySession() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionName", "rtdwh-adhoc-" + UUID.randomUUID());
        payload.put("properties", Map.of("execution.runtime-mode", "batch"));
        JsonNode response = postGateway("/v1/sessions", payload);
        String sessionHandle = response.path("sessionHandle").asText("");
        if (sessionHandle.isBlank()) throw new IllegalStateException("SQL Gateway 未返回 sessionHandle");
        return sessionHandle;
    }

    private void executeGatewaySetupStatement(
            ActiveQuery query,
            String statement,
            int timeoutSeconds
    ) throws Exception {
        query.operationHandle = submitGatewayStatement(query.sessionHandle, statement);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (true) {
            ensureNotCancelled(query);
            if (System.currentTimeMillis() > deadline) {
                cancelGatewayOperation(query);
                throw new IllegalStateException("SQL Gateway Catalog 初始化超时");
            }
            String operationStatus = getGatewayOperationStatus(query);
            if ("FINISHED".equals(operationStatus)) {
                JsonNode result = getGatewayResult("/v1/sessions/" + query.sessionHandle
                        + "/operations/" + query.operationHandle + "/result/0");
                String error = extractGatewayError(result);
                if (error != null) throw new IllegalStateException(error);
                closeGatewayOperation(query);
                return;
            }
            if (FAILED_OPERATION_STATUSES.contains(operationStatus)) {
                JsonNode result = getGatewayResult("/v1/sessions/" + query.sessionHandle
                        + "/operations/" + query.operationHandle + "/result/0");
                String error = extractGatewayError(result);
                throw new IllegalStateException(error == null
                        ? "SQL Gateway Catalog 初始化状态为 " + operationStatus
                        : error);
            }
            sleepForResult();
        }
    }

    private String submitGatewayStatement(String sessionHandle, String sql) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statement", sql);
        payload.put("executionConfig", Map.of("execution.runtime-mode", "batch"));
        JsonNode response = postGateway("/v1/sessions/" + sessionHandle + "/statements", payload);
        String error = extractGatewayError(response);
        if (error != null) throw new IllegalStateException(error);
        String operationHandle = response.path("operationHandle").asText("");
        if (operationHandle.isBlank()) throw new IllegalStateException("SQL Gateway 未返回 operationHandle");
        return operationHandle;
    }

    private boolean fetchGatewayResults(
            ActiveQuery query,
            int maxRows,
            int timeoutSeconds,
            List<String> columns,
            List<List<Object>> rows
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String nextResultUri = "/v1/sessions/" + query.sessionHandle
                + "/operations/" + query.operationHandle + "/result/0";
        boolean truncated = false;

        while (nextResultUri != null) {
            ensureNotCancelled(query);
            if (System.currentTimeMillis() > deadline) {
                cancelGatewayOperation(query);
                throw new IllegalStateException("查询超时（" + timeoutSeconds + " 秒）");
            }

            JsonNode response = getGatewayResult(nextResultUri);
            String jobId = response.path("jobID").asText("");
            if (jobId.isBlank()) jobId = response.path("jobId").asText("");
            if (!jobId.isBlank()) query.flinkJobId = jobId;
            String error = extractGatewayError(response);
            if (error != null) throw new IllegalStateException(error);

            String resultType = response.path("resultType").asText("").toUpperCase(Locale.ROOT);
            if ("NOT_READY".equals(resultType) || resultType.isBlank()) {
                String operationStatus = getGatewayOperationStatus(query);
                if (FAILED_OPERATION_STATUSES.contains(operationStatus)) {
                    throw new IllegalStateException("SQL Gateway 查询状态为 " + operationStatus);
                }
                sleepForResult();
                continue;
            }
            if ("EOS".equals(resultType)) break;
            if (!"PAYLOAD".equals(resultType)) {
                throw new IllegalStateException("SQL Gateway 返回未知结果类型: " + resultType);
            }

            int rowsBeforeBatch = rows.size();
            appendGatewayPayload(response.path("results"), columns, rows, maxRows + 1);
            if (rows.size() > maxRows) {
                rows.remove(rows.size() - 1);
                truncated = true;
                cancelGatewayOperation(query);
                cancelFlinkJob(query);
                break;
            }

            JsonNode next = response.get("nextResultUri");
            nextResultUri = next == null || next.isNull() || next.asText().isBlank() ? null : next.asText();
            if (nextResultUri != null && rows.size() == rowsBeforeBatch) sleepForResult();
        }
        return truncated;
    }

    static void appendGatewayPayload(
            JsonNode results,
            List<String> columns,
            List<List<Object>> rows,
            int rowLimit
    ) {
        if (results == null || results.isNull()) return;
        if (results.isArray()) {
            for (JsonNode result : results) appendGatewayPayload(result, columns, rows, rowLimit);
            return;
        }

        if (columns.isEmpty()) {
            for (JsonNode column : results.path("columns")) {
                columns.add(column.path("name").asText("column_" + (columns.size() + 1)));
            }
        }
        for (JsonNode data : results.path("data")) {
            if (rows.size() >= rowLimit) break;
            JsonNode fields = data.isArray() ? data : data.path("fields");
            List<Object> row = new ArrayList<>();
            if (fields.isArray()) {
                for (JsonNode field : fields) row.add(jsonValue(field));
            }
            rows.add(row);
        }
    }

    private JsonNode postGateway(String path, Map<String, Object> payload) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    gatewayBaseUrl() + path,
                    new HttpEntity<>(payload, headers),
                    String.class
            );
            return readGatewayBody(response.getBody());
        } catch (RestClientResponseException exception) {
            throw gatewayHttpException(exception);
        }
    }

    private JsonNode getGatewayResult(String resultUri) throws Exception {
        String url = resolveGatewayUrl(resultUri);
        url += url.contains("?") ? "&rowFormat=JSON" : "?rowFormat=JSON";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return readGatewayBody(response.getBody());
        } catch (RestClientResponseException exception) {
            throw gatewayHttpException(exception);
        }
    }

    private String getGatewayOperationStatus(ActiveQuery query) throws Exception {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    gatewayBaseUrl() + "/v1/sessions/" + query.sessionHandle
                            + "/operations/" + query.operationHandle + "/status",
                    String.class
            );
            return readGatewayBody(response.getBody()).path("status").asText("").toUpperCase(Locale.ROOT);
        } catch (RestClientResponseException exception) {
            throw gatewayHttpException(exception);
        }
    }

    private JsonNode readGatewayBody(String body) throws Exception {
        return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private IllegalStateException gatewayHttpException(RestClientResponseException exception) {
        try {
            JsonNode response = readGatewayBody(exception.getResponseBodyAsString());
            String detail = extractGatewayError(response);
            if (detail != null) return new IllegalStateException(detail, exception);
        } catch (Exception ignored) {
            // Fall back to the HTTP status below.
        }
        return new IllegalStateException("SQL Gateway 请求失败: HTTP " + exception.getRawStatusCode(), exception);
    }

    static String extractGatewayError(JsonNode response) {
        if (response == null) return null;
        String detail = "";
        JsonNode errors = response.path("errors");
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                String candidate = error.asText("");
                if (candidate.contains("Caused by:") || candidate.length() > detail.length()) detail = candidate;
            }
        }
        if (detail.isBlank()) detail = response.path("error").asText("");
        if (detail.isBlank()) detail = response.path("exception").path("rootCause").asText("");
        if (detail.isBlank()) detail = response.path("exception").path("root_cause").asText("");
        if (detail.isBlank()) return null;
        int causedBy = detail.lastIndexOf("Caused by:");
        String concise = causedBy >= 0 ? detail.substring(causedBy) : detail;
        return concise.length() > 1800 ? concise.substring(0, 1800) + "..." : concise;
    }

    private void cancelGatewayOperation(ActiveQuery query) {
        if (query.sessionHandle == null || query.operationHandle == null) return;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(
                    gatewayBaseUrl() + "/v1/sessions/" + query.sessionHandle
                            + "/operations/" + query.operationHandle + "/cancel",
                    new HttpEntity<>(Map.of(), headers),
                    String.class
            );
        } catch (Exception exception) {
            log.debug("SQL Gateway operation cancel skipped: {}", exception.getMessage());
        }
    }

    private void cancelFlinkJob(ActiveQuery query) {
        if (query.flinkJobId == null || query.flinkJobId.isBlank()) return;
        flinkClusterService.cancelJob(query.flinkJobId);
        query.flinkJobId = null;
    }

    private void closeGatewayResources(ActiveQuery query) {
        if (query.sessionHandle == null) return;
        closeGatewayOperation(query);
        try {
            restTemplate.delete(gatewayBaseUrl() + "/v1/sessions/" + query.sessionHandle);
        } catch (Exception exception) {
            log.debug("SQL Gateway session close skipped: {}", exception.getMessage());
        }
        query.operationHandle = null;
        query.sessionHandle = null;
    }

    private void closeGatewayOperation(ActiveQuery query) {
        if (query.sessionHandle == null || query.operationHandle == null) return;
        try {
            restTemplate.delete(gatewayBaseUrl() + "/v1/sessions/" + query.sessionHandle
                    + "/operations/" + query.operationHandle + "/close");
        } catch (Exception exception) {
            log.debug("SQL Gateway operation close skipped: {}", exception.getMessage());
        } finally {
            query.operationHandle = null;
        }
    }

    private boolean executeViaMetadataJdbc(
            String sql,
            int maxRows,
            int timeout,
            ActiveQuery query,
            List<String> columns,
            List<List<Object>> rows
    ) throws SQLException {
        boolean truncated = false;
        try (Connection connection = DriverManager.getConnection(paimonJdbcUri, paimonJdbcUser, paimonJdbcPassword);
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            query.statement = statement;
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(maxRows + 1);
            ensureNotCancelled(query);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                while (resultSet.next()) {
                    ensureNotCancelled(query);
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) row.add(value(resultSet.getObject(i)));
                    rows.add(row);
                }
            }
        }
        return truncated;
    }

    public void cancelQuery(Long historyId, Long userId) {
        ActiveQuery query = active.get(historyId);
        if (query == null) throw new IllegalStateException("查询已结束，无法取消");
        if (!Objects.equals(query.userId, userId)) throw new IllegalArgumentException("无权取消该查询");
        query.cancel();
        cancelGatewayOperation(query);
        cancelFlinkJob(query);
    }

    public void cancelQueryByRequestId(String requestId, Long userId) {
        Long id = requests.get(requestId);
        if (id == null) throw new IllegalStateException("查询尚未开始或已结束");
        cancelQuery(id, userId);
    }

    @Transactional(readOnly = true)
    public Page<QueryHistory> getQueryHistoryPage(Long userId, int page, int size) {
        return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(
                userId,
                PageRequest.of(
                        Math.max(0, page),
                        Math.max(1, Math.min(size, 100)),
                        Sort.by(Sort.Direction.DESC, "createdAt")
                )
        );
    }

    public List<QueryHistory> getQueryHistory(Long userId) {
        return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Map<String, Object> executeReportQuery(String sql, Long userId) {
        QueryExecuteDTO dto = new QueryExecuteDTO();
        dto.setSql(sql);
        dto.setMaxRows(maxExportRows);
        dto.setTimeoutSeconds(Math.min(defaultTimeout * 5, 1800));
        return execute(dto, userId, QueryType.report, maxExportRows);
    }

    private String validateSql(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        String clean = strip(raw);
        List<String> statements = Arrays.stream(clean.split(";", -1))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
        if (statements.size() != 1) throw new IllegalArgumentException("每次只能执行一条查询语句");
        String first = statements.get(0).split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (!ALLOWED.contains(first) || WRITE.matcher(statements.get(0)).find()) {
            throw new IllegalArgumentException("仅支持安全的查询类 SQL");
        }
        return raw.trim().replaceFirst(";\\s*$", "");
    }

    private String strip(String sql) {
        StringBuilder out = new StringBuilder();
        boolean quote = false;
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (line) {
                if (current == '\n') {
                    line = false;
                    out.append('\n');
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (block) {
                if (current == '*' && next == '/') {
                    block = false;
                    out.append("  ");
                    i++;
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (!quote && current == '-' && next == '-') {
                line = true;
                out.append("  ");
                i++;
                continue;
            }
            if (!quote && current == '/' && next == '*') {
                block = true;
                out.append("  ");
                i++;
                continue;
            }
            if (current == '\'') {
                if (quote && next == '\'') {
                    out.append("  ");
                    i++;
                } else {
                    quote = !quote;
                    out.append(' ');
                }
                continue;
            }
            out.append(quote ? ' ' : current);
        }
        if (quote || block) throw new IllegalArgumentException("SQL 引号或注释未闭合");
        return out.toString();
    }

    private String buildCatalogStatement() {
        return String.format(
                "CREATE CATALOG IF NOT EXISTS paimon WITH "
                        + "('type'='paimon','metastore'='jdbc','uri'='%s','jdbc.user'='%s',"
                        + "'jdbc.password'='%s','catalog-key'='%s','warehouse'='%s')",
                esc(paimonJdbcUri),
                esc(paimonJdbcUser),
                esc(paimonJdbcPassword),
                esc(paimonCatalogKey),
                esc(warehousePath)
        );
    }

    private String gatewayBaseUrl() {
        return sqlGatewayUrl.replaceAll("/+$", "");
    }

    private String resolveGatewayUrl(String resultUri) {
        URI uri = URI.create(resultUri);
        if (uri.isAbsolute()) return uri.toString();
        if (resultUri.startsWith("/")) {
            URI base = URI.create(gatewayBaseUrl());
            return base.getScheme() + "://" + base.getAuthority() + resultUri;
        }
        return gatewayBaseUrl() + "/" + resultUri;
    }

    private void ensureNotCancelled(ActiveQuery query) {
        if (query.cancelled.get()) throw new CancellationException("查询已取消");
    }

    private void sleepForResult() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("查询等待被中断");
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 1800 ? message.substring(0, 1800) + "..." : message;
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String esc(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private Object value(Object raw) {
        if (raw == null || raw instanceof String || raw instanceof Number || raw instanceof Boolean) return raw;
        if (raw instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return String.valueOf(raw);
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.canConvertToLong() ? node.longValue() : node.bigIntegerValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        return node.toString();
    }

    private String csv(Object value) {
        return "\"" + (value == null ? "" : String.valueOf(value).replace("\"", "\"\"")) + "\"";
    }
}
