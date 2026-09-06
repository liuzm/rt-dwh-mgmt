package com.rtdwh.service;

import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.QualityWindow;
import com.rtdwh.dto.QualityOverviewSummary;
import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityCheckRunRepository;
import com.rtdwh.repository.QualityRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class QualityCheckService {
    private static final Pattern SAFE_EXPRESSION = Pattern.compile(
            "^(?!.*(?:;|--|/\\*|\\*/|\\b(?:select|union|insert|update|delete|drop|alter|create|grant|revoke|sleep|benchmark|load_file|outfile)\\b)).+$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @org.springframework.beans.factory.annotation.Value("${quality.schedule.timezone:Asia/Shanghai}")
    private String scheduleTimezone = "Asia/Shanghai";

    private final QualityRuleRepository ruleRepository;
    private final QualityCheckRunRepository runRepository;
    private final QualityCheckPersistenceService persistenceService;
    private final DorisConnectionService dorisConnectionService;
    private final QualityService qualityService;

    public int runAllChecks() {
        return runAllChecksWithSummary("manual").abnormalCount();
    }

    public int runAllChecks(String triggerType) {
        return runAllChecksWithSummary(triggerType).abnormalCount();
    }

    public QualityCheckSummary runAllChecksWithSummary() {
        return runAllChecksWithSummary("manual");
    }

    public QualityCheckSummary runAllChecksWithSummary(String triggerType) {
        return runRules(ruleRepository.findByEnabled(true), normalizeTrigger(triggerType),
                "scheduled".equalsIgnoreCase(triggerType) ? scheduledWindow(java.time.Instant.now()) : null, null);
    }

    QualityWindow scheduledWindow(java.time.Instant now) {
        return QualityWindow.forDate(now.atZone(java.time.ZoneId.of(scheduleTimezone)).toLocalDate().minusDays(1));
    }

    public QualityCheckSummary runAllChecksWithSummary(Long userId) {
        return runAllChecksWithSummary(userId, null);
    }

    public QualityCheckSummary runAllChecksWithSummary(Long userId, LocalDate businessDate) {
        return runRules(qualityService.filterAllowed(userId, ruleRepository.findByEnabled(true)),
                "manual", QualityWindow.forDate(businessDate), userId);
    }

    public record Preview(String checkSql, String scopeKey, QualityWindow window, String emptyPolicy) {}

    public Preview preview(QualityRule rule, LocalDate businessDate, Long userId) {
        qualityService.validateForPreview(rule, userId);
        QualityWindow window = effectiveWindow(rule, QualityWindow.forDate(businessDate));
        String sql = generateCheckSql(rule, window);
        qualityService.validateCheckSql(userId, sql);
        return new Preview(sql, window == null ? "full_table" : window.key(), window, rule.getEmptyPolicy());
    }

    public int runChecksByLayer(String layer) {
        List<QualityRule> rules = ruleRepository.findByLayer(layer).stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .toList();
        return runRules(rules, "manual").abnormalCount();
    }

    public int runCheck(Long ruleId) {
        return runCheckWithSummary(ruleId).abnormalCount();
    }

    public QualityCheckSummary runCheckWithSummary(Long ruleId) {
        QualityRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + ruleId));
        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            throw new IllegalStateException("质量规则未启用: " + ruleId);
        }
        return runRules(List.of(rule), "manual");
    }

    public QualityCheckSummary runCheckWithSummary(Long ruleId, Long userId) {
        return runCheckWithSummary(ruleId, userId, null);
    }

    public QualityCheckSummary runCheckWithSummary(Long ruleId, Long userId, LocalDate businessDate) {
        QualityRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + ruleId));
        qualityService.assertAccess(userId, rule);
        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            throw new IllegalStateException("质量规则未启用: " + ruleId);
        }
        return runRules(List.of(rule), "manual", QualityWindow.forDate(businessDate), userId);
    }

    public int runChecksForTable(String catalog, String database, String table) {
        return runChecksForTableWithSummary(catalog, database, table).abnormalCount();
    }

    public QualityCheckSummary runChecksForTableWithSummary(String catalog, String database, String table) {
        return runRules(snapshotRulesForTable(catalog, database, table), "production");
    }

    public List<QualityRule> snapshotRulesForTable(String catalog, String database, String table) {
        return ruleRepository.findByEnabled(true).stream()
                .filter(rule -> matchesTable(rule, catalog, database, table)).toList();
    }

    public QualityCheckSummary runFrozenProductionRules(List<QualityRule> rules) {
        return runRules(rules, "production");
    }

    public QualityCheckSummary runFrozenProductionRules(List<QualityRule> rules,
                                                        LocalDate start, LocalDate end) {
        return runRules(rules, "production", start == null && end == null ? null
                : new QualityWindow(start == null ? null : start.atStartOfDay(), end == null ? null : end.atStartOfDay()), null);
    }

    @Transactional(readOnly = true)
    public List<QualityCheckRun> listRuns(Long ruleId) {
        return ruleId == null
                ? runRepository.findTop100ByOrderByStartedAtDesc()
                : runRepository.findTop100ByRuleIdOrderByStartedAtDesc(ruleId);
    }

    @Transactional(readOnly = true)
    public List<QualityCheckRun> listRuns(Long ruleId, Long userId) {
        return runRepository.findAll().stream()
                .filter(run -> ruleId == null || ruleId.equals(run.getRuleId()))
                .filter(run -> qualityService.canAccessSnapshot(userId, run.getTargetTable(), run.getLayer()))
                .sorted(Comparator.comparing(QualityCheckRun::getId).reversed()).limit(100).toList();
    }

    @Transactional(readOnly = true)
    public QualityOverviewSummary getOverview() {
        LocalDate today = LocalDate.now();
        LocalDateTime trendStartedAt = today.minusDays(6).atStartOfDay();
        List<QualityOverviewSummary.DailyRunSummary> dailyRuns = runRepository
                .summarizeDailyRuns(trendStartedAt).stream()
                .map(row -> new QualityOverviewSummary.DailyRunSummary(
                        toLocalDate(row[0]), numberValue(row[1]), numberValue(row[2]), numberValue(row[3])))
                .toList();
        Double averageDuration = runRepository.findAverageCompletedDurationMs();
        return new QualityOverviewSummary(
                runRepository.findLatestRunForEachRule(),
                dailyRuns,
                runRepository.countByStartedAtGreaterThanEqual(LocalDateTime.now().minusHours(24)),
                averageDuration == null ? 0L : Math.round(averageDuration));
    }

    @Transactional(readOnly = true)
    public QualityOverviewSummary getOverview(Long userId) { return getOverview(userId, null); }

    public QualityOverviewSummary getOverview(Long userId, LocalDate businessDate) {
        List<QualityCheckRun> runs = runRepository.findAll().stream()
                .filter(run -> qualityService.canAccessSnapshot(userId, run.getTargetTable(), run.getLayer())).toList();
        List<QualityCheckRun> latestRuns = runs.stream()
                .filter(run -> businessDate == null || "full_table".equals(run.getScopeKey())
                        || QualityWindow.forDate(businessDate).key().equals(run.getScopeKey()))
                .collect(Collectors.toMap(QualityCheckRun::getRuleId, Function.identity(),
                        (left, right) -> left.getId() > right.getId() ? left : right,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(QualityCheckRun::getStartedAt).reversed())
                .toList();

        LocalDate today = LocalDate.now();
        LocalDateTime trendStartedAt = today.minusDays(6).atStartOfDay();
        Map<LocalDate, long[]> daily = new java.util.TreeMap<>();
        runs.stream().filter(run -> !run.getStartedAt().isBefore(trendStartedAt)).forEach(run -> {
            long[] values = daily.computeIfAbsent(run.getStartedAt().toLocalDate(), ignored -> new long[3]);
            values[0]++;
            if ("passed".equals(run.getStatus())) values[1]++;
            if ("failed".equals(run.getStatus()) || "error".equals(run.getStatus())) values[2]++;
        });
        List<QualityOverviewSummary.DailyRunSummary> dailyRuns = daily.entrySet().stream()
                .map(entry -> new QualityOverviewSummary.DailyRunSummary(
                        entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .toList();
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long last24h = runs.stream().filter(run -> !run.getStartedAt().isBefore(since)).count();
        long averageDuration = Math.round(runs.stream()
                .filter(run -> run.getDurationMs() != null && !"running".equals(run.getStatus()))
                .mapToLong(QualityCheckRun::getDurationMs).average().orElse(0));
        return new QualityOverviewSummary(latestRuns, dailyRuns, last24h, averageDuration);
    }

    private QualityCheckSummary runRules(List<QualityRule> rules, String triggerType) {
        return runRules(rules, triggerType, null, null);
    }

    private QualityCheckSummary runRules(List<QualityRule> rules, String triggerType, QualityWindow window, Long userId) {
        String batchId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        long started = System.currentTimeMillis();
        int passed = 0;
        int failed = 0;
        int errors = 0;
        int abnormalCount = 0;
        try {
            int recovered = persistenceService.recoverStaleRuns(startedAt.minusMinutes(30));
            if (recovered > 0) log.warn("Recovered {} stale quality check runs", recovered);
        } catch (Exception recoveryError) {
            log.warn("Could not recover stale quality check runs: {}", conciseError(recoveryError));
        }
        for (QualityRule rule : rules) {
            CheckOutcome outcome = checkRule(rule, batchId, triggerType, window, userId);
            abnormalCount += outcome.abnormalCount();
            switch (outcome.status()) {
                case "passed" -> passed++;
                case "failed" -> failed++;
                default -> errors++;
            }
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        long durationMs = System.currentTimeMillis() - started;
        log.info("Quality batch [{}] completed: rules={}, passed={}, failed={}, errors={}, abnormal={}",
                batchId, rules.size(), passed, failed, errors, abnormalCount);
        return new QualityCheckSummary(batchId, rules.size(), passed, failed, errors, abnormalCount,
                startedAt, finishedAt, durationMs);
    }

    private CheckOutcome checkRule(QualityRule rule, String batchId, String triggerType, QualityWindow requestedWindow, Long userId) {
        QualityWindow window = "business_window".equals(rule.getCheckScope()) ? requestedWindow : null;
        QualityCheckRun run;
        try {
            run = persistenceService.startRun(QualityCheckRun.builder()
                .batchId(batchId)
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .ruleType(rule.getRuleType())
                .targetTable(snapshotTarget(rule))
                .layer(rule.getLayer())
                .scopeKey(window == null ? ("business_window".equals(rule.getCheckScope()) ? "missing_window" : "full_table") : window.key())
                .windowStart(window == null ? null : window.start()).windowEnd(window == null ? null : window.end())
                .timeColumn(rule.getTimeColumn()).emptyPolicy(rule.getEmptyPolicy() == null ? "fail" : rule.getEmptyPolicy())
                .targetColumn(rule.getTargetColumn())
                .ruleVersion(rule.getVersion())
                .triggerType(triggerType)
                .engine("doris")
                .status("running")
                .checkSql("-- 正在生成质量检查 SQL --")
                .thresholdValue(rule.getThreshold())
                .startedAt(LocalDateTime.now())
                .build());
        } catch (Exception persistenceError) {
            log.error("Quality rule [{}] could not create run record: {}", rule.getId(), conciseError(persistenceError));
            return new CheckOutcome("error", 1);
        }

        long started = System.currentTimeMillis();
        CheckOutcome outcome;
        String alertLevel = null;
        String alertMessage = null;
        try {
            String sql = generateCheckSql(rule, requestedWindow);
            run.setCheckSql(sql);
            qualityService.validateCheckSql(userId, sql);
            Measurement measurement = executeViaDoris(sql);
            double actualValue = measurement.actual();
            run.setCheckedRows(measurement.rows());
            run.setViolationRows(measurement.violations());
            double threshold = rule.getThreshold() == null ? 0.0 : rule.getThreshold();
            boolean noData = measurement.rows() == 0 && !"allow".equals(rule.getEmptyPolicy());
            boolean exceeded = noData || isThresholdExceeded(rule.getRuleType(), actualValue, threshold);
            run.setActualValue(actualValue);
            run.setStatus(exceeded ? "failed" : "passed");
            if (exceeded) {
                alertMessage = noData ? "检测范围内没有数据，空数据策略要求不通过" : buildAlertMessage(rule, actualValue, threshold);
                alertLevel = noData ? "error" : determineAlertLevel(rule.getRuleType(), actualValue, threshold);
                if (noData) run.setErrorMessage(alertMessage);
                outcome = new CheckOutcome("failed", 1);
            } else {
                outcome = new CheckOutcome("passed", 0);
            }
        } catch (Exception exception) {
            String error = conciseError(exception);
            run.setStatus("error");
            run.setErrorMessage(error);
            alertLevel = "error";
            alertMessage = "质量检查执行失败: " + error;
            outcome = new CheckOutcome("error", 1);
            log.error("Quality rule [{}] execution failed: {}", rule.getId(), error);
        } finally {
            run.setDurationMs(System.currentTimeMillis() - started);
            run.setFinishedAt(LocalDateTime.now());
        }
        try {
            boolean isNewAlert = persistenceService.completeRun(rule, run, alertLevel, alertMessage);
            if (isNewAlert) log.info("Quality rule [{}] opened a new alert", rule.getId());
            return outcome;
        } catch (Exception persistenceError) {
            log.error("Quality rule [{}] could not atomically finish its run and alert state: {}",
                    rule.getId(), conciseError(persistenceError));
            return new CheckOutcome("error", 1);
        }
    }

    private QualityWindow effectiveWindow(QualityRule rule, QualityWindow requested) {
        if (!"business_window".equals(rule.getCheckScope())) return null;
        if (requested == null) throw new IllegalArgumentException("业务窗口规则必须指定业务日期或产出窗口");
        if (rule.getTimeColumn() == null || rule.getTimeColumn().isBlank())
            throw new IllegalArgumentException("业务时间字段不能为空");
        return requested;
    }

    private String snapshotTarget(QualityRule rule) {
        try { return qualifiedTable(rule).replace("`", ""); }
        catch (IllegalArgumentException invalid) { return rule.getTargetTable(); }
    }

    String generateCheckSql(QualityRule rule) { return generateCheckSql(rule, null); }

    String generateCheckSql(QualityRule rule, QualityWindow requested) {
        QualityWindow window = effectiveWindow(rule, requested);
        String table = qualifiedTable(rule);
        String column = rule.getTargetColumn() == null || rule.getTargetColumn().isBlank()
                ? null : DorisConnectionService.quoteIdentifier(stripQuotes(rule.getTargetColumn()));
        String numerator;
        String metric;
        switch (rule.getRuleType()) {
            case "null_rate" -> {
                requireColumn(column, rule.getRuleType());
                numerator = "SUM(CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END)";
                metric = "COALESCE(CAST(" + numerator + " AS DOUBLE) / NULLIF(COUNT(*), 0), 0.0)";
            }
            case "uniqueness" -> {
                requireColumn(column, rule.getRuleType());
                numerator = "COUNT(*) - COUNT(DISTINCT " + column + ")";
                metric = "COALESCE(CAST(COUNT(DISTINCT " + column + ") AS DOUBLE) / NULLIF(COUNT(*), 0), 1.0)";
            }
            case "volume_compare" -> {
                numerator = "NULL"; // A volume threshold has no row-level violation count.
                metric = "CAST(COUNT(*) AS DOUBLE)";
            }
            case "range_check" -> {
                requireColumn(column, rule.getRuleType());
                String expression = validateExpression(rule.getExpression());
                // SQL UNKNOWN (including NULL) is not evidence of a valid value.
                numerator = "SUM(CASE WHEN (" + expression + ") THEN 0 ELSE 1 END)";
                metric = "COALESCE(CAST(" + numerator + " AS DOUBLE) / NULLIF(COUNT(*), 0), 0.0)";
            }
            default -> throw new IllegalArgumentException("不支持的质量规则类型: " + rule.getRuleType());
        }
        return "SELECT " + metric + " AS actual_value, COUNT(*) AS checked_rows, "
                + ("NULL".equals(numerator) ? numerator : "COALESCE(" + numerator + ", 0)")
                + " AS violation_rows FROM " + table
                + (window == null ? "" : " WHERE " + window.predicate(DorisConnectionService.quoteIdentifier(rule.getTimeColumn())));
    }

    private record Measurement(double actual, long rows, Long violations) {}

    private Measurement executeViaDoris(String sql) throws Exception {
        try (Connection connection = dorisConnectionService.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(120);
            statement.execute("SWITCH " + DorisConnectionService.quoteIdentifier(dorisConnectionService.getCatalog()));
            statement.execute("SET query_timeout = 120");
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (!resultSet.next()) throw new IllegalStateException("Doris 未返回质量指标");
                double actual = resultSet.getDouble(1);
                if (resultSet.wasNull() || !Double.isFinite(actual)) throw new IllegalStateException("Doris 返回无效质量指标");
                long rows = resultSet.getLong(2);
                if (resultSet.wasNull() || rows < 0) throw new IllegalStateException("Doris 未返回有效检测行数");
                long violations = resultSet.getLong(3);
                return new Measurement(actual, rows, resultSet.wasNull() ? null : violations);
            }
        }
    }

    private String qualifiedTable(QualityRule rule) {
        TableRef table = resolveTable(rule);
        return quotePath(table.catalog(), table.database(), table.table());
    }

    private String quotePath(String... parts) {
        return Arrays.stream(parts)
                .map(DorisConnectionService::quoteIdentifier)
                .reduce((left, right) -> left + "." + right)
                .orElseThrow();
    }

    private String stripQuotes(String value) {
        return value == null ? null : value.trim().replace("`", "");
    }

    private String validateExpression(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("范围检查表达式不能为空");
        if (!SAFE_EXPRESSION.matcher(expression.trim()).matches()) {
            throw new IllegalArgumentException("范围检查表达式包含不安全内容");
        }
        return expression.trim();
    }

    private void requireColumn(String column, String ruleType) {
        if (column == null) throw new IllegalArgumentException(ruleType + " 规则必须配置目标字段");
    }

    private boolean isThresholdExceeded(String ruleType, double actual, double threshold) {
        return switch (ruleType) {
            case "null_rate", "range_check" -> actual > threshold;
            case "uniqueness", "volume_compare" -> actual < threshold;
            default -> throw new IllegalArgumentException("不支持的质量规则类型: " + ruleType);
        };
    }

    private String determineAlertLevel(String ruleType, double actual, double threshold) {
        double delta = List.of("null_rate", "range_check").contains(ruleType)
                ? actual - threshold : threshold - actual;
        double deviation = Math.max(0.0, delta) / Math.max(Math.abs(threshold), 0.01);
        if (deviation >= 1.0) return "error";
        if (deviation >= 0.25) return "warn";
        return "info";
    }

    private String buildAlertMessage(QualityRule rule, double actual, double threshold) {
        String direction = List.of("null_rate", "range_check").contains(rule.getRuleType()) ? "超过" : "低于";
        return String.format("质量检查异常: 表 %s 列 %s 的%s实际值 %.4f，%s阈值 %.4f",
                rule.getTargetTable(), rule.getTargetColumn() == null ? "(全表)" : rule.getTargetColumn(),
                ruleTypeLabel(rule.getRuleType()), actual, direction, threshold);
    }

    private String ruleTypeLabel(String ruleType) {
        return switch (ruleType) {
            case "null_rate" -> "空值率";
            case "uniqueness" -> "唯一率";
            case "volume_compare" -> "数据量";
            case "range_check" -> "越界率";
            default -> ruleType;
        };
    }

    private String normalizeTrigger(String triggerType) {
        if ("production".equalsIgnoreCase(triggerType)) return "production";
        return "scheduled".equalsIgnoreCase(triggerType) ? "scheduled" : "manual";
    }

    private boolean matchesTable(QualityRule rule, String catalog, String database, String table) {
        if (catalog == null || database == null || table == null) return false;
        try {
            TableRef resolved = resolveTable(rule);
            return resolved.catalog().equalsIgnoreCase(catalog)
                    && resolved.database().equalsIgnoreCase(database)
                    && resolved.table().equalsIgnoreCase(table);
        } catch (IllegalArgumentException invalidRule) {
            log.warn("Quality rule [{}] has an invalid target table: {}", rule.getId(), conciseError(invalidRule));
            return false;
        }
    }

    private TableRef resolveTable(QualityRule rule) {
        String raw = stripQuotes(rule.getTargetTable());
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("表名不能为空");
        String[] parts = raw.split("\\.", -1);
        String catalog = dorisConnectionService.getCatalog();
        String database = rule.getLayer() == null || rule.getLayer().isBlank()
                ? dorisConnectionService.getDatabase() : stripQuotes(rule.getLayer()).toLowerCase(Locale.ROOT);
        TableRef table = switch (parts.length) {
            case 1 -> new TableRef(catalog, database, parts[0]);
            case 2 -> new TableRef(catalog, parts[0], parts[1]);
            case 3 -> new TableRef(parts[0], parts[1], parts[2]);
            default -> throw new IllegalArgumentException("表名必须是 table、database.table 或 catalog.database.table");
        };
        DorisConnectionService.quoteIdentifier(table.catalog());
        DorisConnectionService.quoteIdentifier(table.database());
        DorisConnectionService.quoteIdentifier(table.table());
        return table;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private String conciseError(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() > 1800 ? message.substring(0, 1800) + "..." : message;
    }

    private record CheckOutcome(String status, int abnormalCount) {
    }

    private record TableRef(String catalog, String database, String table) {
    }
}
