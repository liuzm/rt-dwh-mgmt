package com.rtdwh.service;

import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.repository.QualityAlertRepository;
import com.rtdwh.repository.QualityRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCheckService {

    private final QualityRuleRepository ruleRepository;
    private final QualityAlertRepository alertRepository;
    private final AlertNotifyService alertNotifyService;

    @Value("${paimon.jdbc-uri}")
    private String paimonJdbcUri;

    @Value("${paimon.jdbc-user}")
    private String paimonJdbcUser;

    @Value("${paimon.jdbc-password}")
    private String paimonJdbcPassword;

    @Value("${paimon.warehouse-path}")
    private String paimonWarehousePath;

    @Value("${paimon.catalog-key}")
    private String paimonCatalogKey;

    @Value("${flink.sql-gateway.enabled:false}")
    private boolean sqlGatewayEnabled;

    @Value("${flink.sql-gateway.url:http://localhost:9083}")
    private String sqlGatewayUrl;

    /**
     * Run all enabled quality rules and generate alerts for failures.
     *
     * For each enabled rule:
     * 1. Generate a check SQL based on rule type
     * 2. Execute the SQL against Paimon (via SQL Gateway or metastore fallback)
     * 3. Compare actual value against threshold
     * 4. If threshold exceeded → create QualityAlert and send notification
     *
     * Returns the number of alerts generated.
     */
    @Transactional
    public int runAllChecks() {
        log.info("Starting quality check run for all enabled rules...");
        List<QualityRule> rules = ruleRepository.findByEnabled(true);
        int alertCount = 0;

        for (QualityRule rule : rules) {
            try {
                alertCount += checkRule(rule);
            } catch (Exception e) {
                log.error("Quality check failed for rule [{}] ({}): {}", rule.getId(), rule.getRuleName(), e.getMessage());
                // Create error-level alert for the check failure itself
                createAlert(rule, -1.0, rule.getThreshold(), "error",
                        "质量检查执行失败: " + e.getMessage());
                alertCount++;
            }
        }

        log.info("Quality check completed. {} alerts generated from {} rules.", alertCount, rules.size());
        return alertCount;
    }

    /**
     * Run quality checks for a specific layer.
     */
    @Transactional
    public int runChecksByLayer(String layer) {
        List<QualityRule> rules = ruleRepository.findByLayerAndRuleType(layer, null);
        // Filter to enabled rules only
        rules = rules.stream().filter(QualityRule::getEnabled).toList();

        int alertCount = 0;
        for (QualityRule rule : rules) {
            try {
                alertCount += checkRule(rule);
            } catch (Exception e) {
                log.error("Quality check failed for rule [{}]: {}", rule.getRuleName(), e.getMessage());
                alertCount++;
            }
        }
        return alertCount;
    }

    /** Run one enabled quality rule by id. */
    @Transactional
    public int runCheck(Long ruleId) {
        QualityRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + ruleId));
        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            throw new IllegalStateException("质量规则未启用: " + ruleId);
        }
        return checkRule(rule);
    }

    /**
     * Check a single quality rule.
     * Returns 1 if an alert was generated, 0 otherwise.
     */
    private int checkRule(QualityRule rule) {
        String checkSql = generateCheckSql(rule);
        if (checkSql == null) {
            log.warn("Cannot generate check SQL for rule [{}] type={}", rule.getRuleName(), rule.getRuleType());
            return 0;
        }

        log.debug("Executing quality check SQL for rule [{}]: {}", rule.getRuleName(), checkSql);

        double actualValue = executeCheckQuery(checkSql);
        double threshold = rule.getThreshold() != null ? rule.getThreshold() : 0.0;

        // Determine if threshold is exceeded (depends on rule type)
        boolean exceeded = isThresholdExceeded(rule.getRuleType(), actualValue, threshold);

        if (exceeded) {
            String level = determineAlertLevel(actualValue, threshold);
            String message = buildAlertMessage(rule, actualValue, threshold);
            createAlert(rule, actualValue, threshold, level, message);

            // Send notification via configured channels
            alertNotifyService.sendQualityAlert(rule, actualValue, threshold, message);

            log.warn("Quality alert: rule [{}] on {}.{} — actual={}, threshold={}",
                    rule.getRuleName(), rule.getTargetTable(), rule.getTargetColumn(),
                    actualValue, threshold);
            return 1;
        }

        log.debug("Quality check passed: rule [{}] actual={}, threshold={}",
                rule.getRuleName(), actualValue, threshold);
        return 0;
    }

    /**
     * Validate that a table/column name contains only safe characters.
     * Only alphanumeric, underscore, dot, and backtick are allowed.
     */
    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\.`-]+$");

    private String sanitizeIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (!SAFE_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(fieldName + " 包含非法字符");
        }
        return identifier;
    }

    /**
     * Generate check SQL based on rule type.
     */
    private String generateCheckSql(QualityRule rule) {
        String table = sanitizeIdentifier(rule.getTargetTable(), "表名");
        String column = rule.getTargetColumn() != null ? sanitizeIdentifier(rule.getTargetColumn(), "列名") : null;

        if (table == null || table.isEmpty()) return null;

        switch (rule.getRuleType()) {
            case "null_rate":
                // NULL rate: COUNT(*) WHERE column IS NULL / COUNT(*)
                if (column == null || column.isEmpty()) return null;
                return String.format(
                    "SELECT CAST(COUNT(CASE WHEN `%s` IS NULL THEN 1 END) AS DOUBLE) / CAST(COUNT(*) AS DOUBLE) AS null_rate FROM `%s`",
                    column, table);

            case "uniqueness":
                // Uniqueness: COUNT(DISTINCT column) / COUNT(*)
                if (column == null || column.isEmpty()) return null;
                return String.format(
                    "SELECT CAST(COUNT(DISTINCT `%s`) AS DOUBLE) / CAST(COUNT(*) AS DOUBLE) AS uniqueness_rate FROM `%s`",
                    column, table);

            case "volume_compare":
                // Volume: just get COUNT(*) as the value, threshold is min expected row count
                return String.format("SELECT CAST(COUNT(*) AS DOUBLE) AS row_count FROM `%s`", table);

            case "range_check":
                // Range check: COUNT(*) WHERE column outside range / COUNT(*)
                if (rule.getExpression() != null && !rule.getExpression().isEmpty()) {
                    return String.format(
                        "SELECT CAST(COUNT(CASE WHEN NOT (%s) THEN 1 END) AS DOUBLE) / CAST(COUNT(*) AS DOUBLE) AS out_of_range_rate FROM `%s`",
                        rule.getExpression(), table);
                }
                return null;

            default:
                log.warn("Unknown rule type: {}", rule.getRuleType());
                return null;
        }
    }

    /**
     * Execute the check query and return the numeric result.
     * Uses SQL Gateway if available, otherwise falls back to Paimon metastore JDBC.
     */
    private double executeCheckQuery(String sql) {
        if (sqlGatewayEnabled) {
            return executeViaSqlGateway(sql);
        } else {
            return executeViaPaimonMetastore(sql);
        }
    }

    /**
     * Execute query via Flink SQL Gateway for Paimon data queries.
     */
    private double executeViaSqlGateway(String sql) {
        String gatewayHostPort = sqlGatewayUrl.replace("http://", "").replace("https://", "");
        String jdbcUrl = "jdbc:hive2://" + gatewayHostPort + "/default;transportMode=http;httpPath=flink/sql-gateway";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "anonymous", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(60);
                stmt.setMaxRows(1);

                // Register Paimon catalog
                stmt.execute(
                    "CREATE CATALOG IF NOT EXISTS paimon WITH (" +
                    "'type' = 'paimon', " +
                    "'metastore' = 'jdbc', " +
                    "'uri' = '" + paimonJdbcUri + "', " +
                    "'jdbc.user' = '" + paimonJdbcUser + "', " +
                    "'jdbc.password' = '" + paimonJdbcPassword + "', " +
                    "'catalog-key' = '" + paimonCatalogKey + "', " +
                    "'warehouse' = '" + paimonWarehousePath + "'" +
                    ")"
                );
                stmt.execute("USE CATALOG paimon");

                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            }
        } catch (Exception e) {
            log.error("SQL Gateway quality check query failed: {}", e.getMessage());
            throw new RuntimeException("Quality check query failed via SQL Gateway: " + e.getMessage(), e);
        }
        return 0.0;
    }

    /**
     * Execute query via Paimon metastore JDBC (fallback).
     * This can only query metadata tables, not actual data.
     * For data quality checks, SQL Gateway is required.
     */
    private double executeViaPaimonMetastore(String sql) {
        // Safety check: only allow simple metadata queries through metastore
        // Data queries (SELECT COUNT etc.) require SQL Gateway
        if (!isMetadataQuery(sql)) {
            throw new RuntimeException(
                "数据质量检查需要 Flink SQL Gateway 支持才能查询 Paimon 数据表。当前 SQL Gateway 未启用，无法执行: " + sql);
        }

        try (Connection conn = DriverManager.getConnection(paimonJdbcUri, paimonJdbcUser, paimonJdbcPassword)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(60);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Paimon metastore quality check query failed: {}", e.getMessage());
            throw new RuntimeException("Quality check query failed: " + e.getMessage(), e);
        }
        return 0.0;
    }

    /**
     * Check if a SQL query is a simple metadata query that can run on Paimon metastore MySQL.
     */
    private boolean isMetadataQuery(String sql) {
        String lower = sql.toLowerCase().trim();
        // Only allow queries that reference paimon_catalog tables or basic MySQL metadata
        return lower.contains("paimon_catalog_") ||
               lower.startsWith("select count(*) from information_schema") ||
               Pattern.compile("select.*from\\s+`paimon").matcher(lower).find();
    }

    /**
     * Determine if a threshold is exceeded based on rule type.
     * - null_rate: actual > threshold (e.g. null rate > 5%)
     * - uniqueness: actual < threshold (e.g. uniqueness < 95%)
     * - volume_compare: actual < threshold (e.g. row count < min expected)
     * - range_check: actual > threshold (e.g. out-of-range rate > tolerance)
     */
    private boolean isThresholdExceeded(String ruleType, double actual, double threshold) {
        switch (ruleType) {
            case "null_rate":
            case "range_check":
                return actual > threshold;
            case "uniqueness":
            case "volume_compare":
                return actual < threshold;
            default:
                return actual > threshold;
        }
    }

    /**
     * Determine alert level based on how far the actual value deviates from threshold.
     */
    private String determineAlertLevel(double actual, double threshold) {
        double deviation = Math.abs(actual - threshold) / Math.max(threshold, 0.01);
        if (deviation > 2.0) return "error";       // >200% deviation
        if (deviation > 1.0) return "warn";         // >100% deviation
        return "info";
    }

    /**
     * Build a human-readable alert message.
     */
    private String buildAlertMessage(QualityRule rule, double actual, double threshold) {
        String direction = switch (rule.getRuleType()) {
            case "null_rate", "range_check" -> "超过";
            case "uniqueness", "volume_compare" -> "低于";
            default -> "超过";
        };

        return String.format("质量检查异常: 表 %s 列 %s 的 %s 实际值 %.4f %s阈值 %.4f",
                rule.getTargetTable(),
                rule.getTargetColumn() != null ? rule.getTargetColumn() : "(全表)",
                rule.getRuleType(),
                actual,
                direction,
                threshold);
    }

    /**
     * Create a QualityAlert record.
     */
    private void createAlert(QualityRule rule, double actualValue, double thresholdValue,
                             String level, String message) {
        QualityAlert alert = QualityAlert.builder()
                .ruleType(rule.getRuleType())
                .targetTable(rule.getTargetTable())
                .targetColumn(rule.getTargetColumn())
                .actualValue(actualValue)
                .thresholdValue(thresholdValue)
                .message(message)
                .level(level)
                .ruleId(rule.getId())
                .resolved(false)
                .triggeredAt(LocalDateTime.now())
                .build();
        alertRepository.save(alert);
    }
}
