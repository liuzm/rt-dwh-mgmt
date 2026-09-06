package com.rtdwh.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {
    private static final Set<String> ALLOWED = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH");
    private static final Pattern WRITE = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|CALL|SET|USE|OUTFILE)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private final QueryHistoryRepository queryHistoryRepository;
    private final DorisConnectionService dorisConnectionService;
    private final QueryAccessScopeService accessScopeService;

    @Value("${query.max-rows}") private int defaultMaxRows;
    @Value("${query.max-export-rows}") private int maxExportRows;
    @Value("${query.timeout-seconds}") private int defaultTimeout;
    @Value("${query.max-concurrent-per-user:2}") private int maxConcurrentPerUser;
    @Value("${query.queue-wait-seconds:3}") private int queueWaitSeconds;
    @Value("${query.budget.scanned-bytes:5368709120}") private long scannedBytesBudget;
    @Value("${query.budget.cpu-ms:30000}") private long cpuMsBudget;
    @Value("${query.budget.peak-memory-bytes:2147483648}") private long peakMemoryBudget;

    private final ConcurrentHashMap<Long, ActiveQuery> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Semaphore> querySlots = new ConcurrentHashMap<>();

    private record QueryPermit(Semaphore semaphore, long waitMs) implements AutoCloseable {
        @Override public void close() { semaphore.release(); }
    }

    private static final class ActiveQuery {
        final Long userId;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Statement statement;

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
        try (QueryPermit permit = acquirePermit(userId)) {
            return executeWithPermit(dto, userId, type, rowLimit, sql, permit.waitMs());
        }
    }

    private Map<String, Object> executeWithPermit(QueryExecuteDTO dto, Long userId, QueryType type,
                                                  int rowLimit, String sql, long queueWaitMs) {
        int maxRows = Math.max(1, Math.min(dto.getMaxRows() == null ? defaultMaxRows : dto.getMaxRows(), rowLimit));
        int timeout = Math.max(1, Math.min(dto.getTimeoutSeconds() == null ? defaultTimeout : dto.getTimeoutSeconds(), 1800));
        String catalog = defaultIfBlank(dto.getCatalog(), dorisConnectionService.getCatalog());
        String database = defaultIfBlank(dto.getDatabase(), dorisConnectionService.getDatabase());
        accessScopeService.validateDoris(userId, sql, catalog, database);
        String requestId = dto.getRequestId() == null || dto.getRequestId().isBlank()
                ? UUID.randomUUID().toString()
                : dto.getRequestId();
        QueryHistory history = queryHistoryRepository.save(QueryHistory.builder()
                .userId(userId)
                .sqlText(sql)
                .queryType(type)
                .queryEngine("doris")
                .traceId(requestId)
                .queueWaitMs(queueWaitMs)
                .status(QueryStatus.running)
                .build());
        long historyId = history.getId();
        ActiveQuery query = new ActiveQuery(userId);
        active.put(historyId, query);
        requests.put(requestId, historyId);

        long started = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<DataServiceContractService.Column> columnSchema = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        QueryStatus status = QueryStatus.success;
        String error = null;
        boolean truncated = false;
        AtomicBoolean monitorRunning = new AtomicBoolean(true);
        AtomicReference<DorisConnectionService.QueryRuntimeStats> runtimeStats = new AtomicReference<>();
        CompletableFuture<Void> monitor = CompletableFuture.runAsync(
                () -> monitorRuntimeStats(requestId, monitorRunning, runtimeStats));
        try {
            truncated = executeViaDorisJdbc(
                    sql, catalog, database, requestId, maxRows, timeout, query, columns, rows, columnSchema);
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
        } finally {
            monitorRunning.set(false);
            active.remove(historyId);
            requests.remove(requestId, historyId);
            query.statement = null;
        }
        long duration = System.currentTimeMillis() - started;

        monitor.cancel(true);
        mergeRuntimeStats(runtimeStats, dorisConnectionService.getQueryRuntimeStats(requestId));
        String dorisQueryId = dorisConnectionService.getQueryIdByTraceId(requestId);
        DorisConnectionService.QueryRuntimeStats metrics = runtimeStats.get();

        history.setQueryId(dorisQueryId);
        applyRuntimeStats(history, metrics);
        evaluateBudget(history);
        history.setResultRowCount(rows.size());
        history.setDurationMs(duration);
        history.setStatus(status);
        history.setErrorMsg(error);
        queryHistoryRepository.save(history);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("columnSchema", columnSchema);
        result.put("rows", rows);
        result.put("rowCount", rows.size());
        result.put("durationMs", duration);
        result.put("status", status.name());
        result.put("errorMsg", error);
        result.put("historyId", historyId);
        result.put("requestId", requestId);
        result.put("truncated", truncated);
        result.put("engine", "doris");
        result.put("catalog", catalog);
        result.put("database", database);
        result.put("traceId", requestId);
        result.put("queryId", dorisQueryId);
        result.put("scannedRows", metrics == null ? null : metrics.scannedRows());
        result.put("scannedBytes", metrics == null ? null : metrics.scannedBytes());
        result.put("cpuMs", metrics == null ? null : metrics.cpuMs());
        result.put("peakMemoryBytes", metrics == null ? null : metrics.peakMemoryBytes());
        result.put("localScanBytes", metrics == null ? null : metrics.localScanBytes());
        result.put("remoteScanBytes", metrics == null ? null : metrics.remoteScanBytes());
        result.put("cacheWriteBytes", metrics == null ? null : metrics.cacheWriteBytes());
        result.put("queueWaitMs", queueWaitMs);
        result.put("costScore", history.getCostScore());
        result.put("budgetExceeded", history.getBudgetExceeded());
        result.put("budgetReason", history.getBudgetReason());
        return result;
    }

    private QueryPermit acquirePermit(Long userId) {
        int concurrencyLimit = Math.max(1, maxConcurrentPerUser);
        Semaphore semaphore = querySlots.computeIfAbsent(userId, ignored -> new Semaphore(concurrencyLimit, true));
        long started = System.nanoTime();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(Math.max(0, queueWaitSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待查询执行槽位时被中断");
        }
        if (!acquired) {
            throw new IllegalStateException("查询队列等待超时，请稍后重试（并发上限 " + concurrencyLimit + "）");
        }
        return new QueryPermit(semaphore, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private void monitorRuntimeStats(
            String traceId,
            AtomicBoolean running,
            AtomicReference<DorisConnectionService.QueryRuntimeStats> current
    ) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(150L);
                if (!running.get()) return;
                mergeRuntimeStats(current, dorisConnectionService.getQueryRuntimeStats(traceId));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Runtime statistics are best effort and must never fail the user query.
            }
        }
    }

    private void mergeRuntimeStats(
            AtomicReference<DorisConnectionService.QueryRuntimeStats> current,
            DorisConnectionService.QueryRuntimeStats sample
    ) {
        if (sample != null) current.updateAndGet(previous -> previous == null ? sample : previous.merge(sample));
    }

    private void applyRuntimeStats(QueryHistory history, DorisConnectionService.QueryRuntimeStats metrics) {
        if (metrics == null) return;
        history.setScannedRows(metrics.scannedRows());
        history.setScannedBytes(metrics.scannedBytes());
        history.setCpuMs(metrics.cpuMs());
        history.setPeakMemoryBytes(metrics.peakMemoryBytes());
        history.setLocalScanBytes(metrics.localScanBytes());
        history.setRemoteScanBytes(metrics.remoteScanBytes());
        history.setCacheWriteBytes(metrics.cacheWriteBytes());
    }

    private void evaluateBudget(QueryHistory history) {
        List<Double> ratios = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        addBudgetMetric("扫描量", history.getScannedBytes(), scannedBytesBudget, ratios, reasons);
        addBudgetMetric("CPU", history.getCpuMs(), cpuMsBudget, ratios, reasons);
        addBudgetMetric("峰值内存", history.getPeakMemoryBytes(), peakMemoryBudget, ratios, reasons);
        if (!ratios.isEmpty()) {
            double score = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0D) * 100D;
            history.setCostScore(Math.round(score * 10D) / 10D);
        }
        history.setBudgetExceeded(!reasons.isEmpty());
        history.setBudgetReason(reasons.isEmpty() ? null : String.join("；", reasons));
    }

    private void addBudgetMetric(String name, Long actual, long budget,
                                 List<Double> ratios, List<String> reasons) {
        if (actual == null || budget <= 0) return;
        ratios.add(actual.doubleValue() / budget);
        if (actual > budget) reasons.add(name + " " + actual + " > " + budget);
    }

    private boolean executeViaDorisJdbc(
            String sql,
            String catalog,
            String database,
            String traceId,
            int maxRows,
            int timeout,
            ActiveQuery query,
            List<String> columns,
            List<List<Object>> rows,
            List<DataServiceContractService.Column> columnSchema
    ) throws SQLException {
        boolean truncated = false;
        try (Connection connection = dorisConnectionService.getConnection()) {
            try (Statement session = connection.createStatement()) {
                session.setQueryTimeout(Math.min(timeout, 10));
                session.execute("SWITCH " + DorisConnectionService.quoteIdentifier(catalog));
                session.execute("USE " + DorisConnectionService.quoteIdentifier(database));
                session.execute("SET query_timeout = " + timeout);
                session.execute("SET exec_mem_limit = " + dorisConnectionService.getExecMemLimitBytes());
                executeOptionalSessionSetting(session, "SET workload_group = "
                        + DorisConnectionService.quoteLiteral(dorisConnectionService.getWorkloadGroup()));
                executeOptionalSessionSetting(session, "SET session_context = "
                        + DorisConnectionService.quoteLiteral("trace_id:" + traceId));
            }
            try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                query.statement = statement;
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(maxRows + 1);
                ensureNotCancelled(query);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    columnSchema.addAll(DataServiceContractService.readColumns(metadata));
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
        }
        return truncated;
    }

    private void executeOptionalSessionSetting(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (SQLException exception) {
            log.warn("Optional Doris session setting was skipped: {}", exception.getMessage());
        }
    }

    public void cancelQuery(Long historyId, Long userId) {
        ActiveQuery query = active.get(historyId);
        if (query == null) throw new IllegalStateException("查询已结束，无法取消");
        if (!Objects.equals(query.userId, userId)) throw new IllegalArgumentException("无权取消该查询");
        query.cancel();
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

    @Transactional(readOnly = true)
    public Map<String, Object> getQueryProfile(Long historyId, Long userId) {
        QueryHistory history = queryHistoryRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new IllegalArgumentException("查询记录不存在或无权访问"));
        if (history.getQueryId() == null || history.getQueryId().isBlank()) {
            throw new IllegalStateException("该查询没有可用的 Doris Query ID");
        }
        return Map.of("queryId", history.getQueryId(),
                "profile", dorisConnectionService.getQueryProfile(history.getQueryId()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGovernanceStats(Long userId) {
        List<QueryHistory> history = queryHistoryRepository.findTop1000ByUserIdOrderByCreatedAtDesc(userId);
        long success = history.stream().filter(item -> item.getStatus() == QueryStatus.success).count();
        long failed = history.stream().filter(item -> item.getStatus() == QueryStatus.failed).count();
        List<Long> durations = history.stream().map(QueryHistory::getDurationMs).filter(Objects::nonNull).sorted().toList();
        long p95 = durations.isEmpty() ? 0L : durations.get(Math.min(durations.size() - 1,
                (int) Math.ceil(durations.size() * 0.95) - 1));
        List<QueryHistory> slowQueries = history.stream().filter(item -> item.getDurationMs() != null)
                .sorted((left, right) -> Long.compare(right.getDurationMs(), left.getDurationMs()))
                .limit(10).toList();
        List<QueryHistory> costlyQueries = history.stream().filter(item -> item.getCostScore() != null)
                .sorted((left, right) -> Double.compare(right.getCostScore(), left.getCostScore()))
                .limit(10).toList();
        long budgetExceeded = history.stream().filter(item -> Boolean.TRUE.equals(item.getBudgetExceeded())).count();
        double averageQueueWait = history.stream().map(QueryHistory::getQueueWaitMs).filter(Objects::nonNull)
                .mapToLong(Long::longValue).average().orElse(0D);
        Semaphore slots = querySlots.get(userId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sampleSize", history.size());
        stats.put("successCount", success);
        stats.put("failedCount", failed);
        stats.put("successRate", history.isEmpty() ? 0D : success * 100D / history.size());
        stats.put("p95DurationMs", p95);
        stats.put("runningCount", active.values().stream().filter(query -> Objects.equals(query.userId, userId)).count());
        stats.put("queuedCount", slots == null ? 0 : slots.getQueueLength());
        stats.put("averageQueueWaitMs", Math.round(averageQueueWait * 10D) / 10D);
        stats.put("concurrencyLimit", Math.max(1, maxConcurrentPerUser));
        stats.put("budgetExceededCount", budgetExceeded);
        stats.put("budget", Map.of(
                "scannedBytes", scannedBytesBudget,
                "cpuMs", cpuMsBudget,
                "peakMemoryBytes", peakMemoryBudget));
        stats.put("slowQueries", slowQueries);
        stats.put("costlyQueries", costlyQueries);
        return stats;
    }

    public Map<String, Object> executeReportQuery(String sql, Long userId) {
        return executeReportQuery(sql, userId, maxExportRows);
    }

    public Map<String, Object> executeReportQuery(String sql, Long userId, int requestedMaxRows) {
        QueryExecuteDTO dto = new QueryExecuteDTO();
        dto.setSql(sql);
        int maxRows = Math.max(1, Math.min(requestedMaxRows, maxExportRows));
        dto.setMaxRows(maxRows);
        dto.setTimeoutSeconds(Math.min(defaultTimeout * 5, 1800));
        return execute(dto, userId, QueryType.report, maxRows);
    }

    public Map<String, Object> executeDataServiceQuery(String sql, Long userId, String catalog,
                                                       String database, int requestedMaxRows, int timeoutSeconds) {
        QueryExecuteDTO dto = new QueryExecuteDTO();
        dto.setSql(sql);
        dto.setCatalog(catalog);
        dto.setDatabase(database);
        int maxRows = Math.max(1, Math.min(requestedMaxRows, maxExportRows));
        dto.setMaxRows(maxRows);
        dto.setTimeoutSeconds(Math.max(1, Math.min(timeoutSeconds, 1800)));
        return execute(dto, userId, QueryType.data_service, maxRows);
    }

    public String validateReadOnlySql(String sql) { return validateSql(sql); }

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

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private void ensureNotCancelled(ActiveQuery query) {
        if (query.cancelled.get()) throw new CancellationException("查询已取消");
    }

    private String conciseError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 1800 ? message.substring(0, 1800) + "..." : message;
    }

    private Object value(Object raw) {
        if (raw == null || raw instanceof String || raw instanceof Number || raw instanceof Boolean) return raw;
        if (raw instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return String.valueOf(raw);
    }

    private String csv(Object value) {
        return "\"" + (value == null ? "" : String.valueOf(value).replace("\"", "\"\"")) + "\"";
    }
}
