package com.rtdwh.service;

import com.rtdwh.entity.SystemSetting;
import com.rtdwh.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private static final String PREFIX = "flink.";
    private static final String REST_URL = PREFIX + "rest-api-url";
    private static final String SUBMISSION_MODE = PREFIX + "submission-mode";
    private static final String SAVEPOINT_DIR = PREFIX + "savepoint-dir";
    private static final String SQL_GATEWAY_ENABLED = PREFIX + "sql-gateway-enabled";
    private static final String SQL_GATEWAY_URL = PREFIX + "sql-gateway-url";
    private static final String EXPECTED_VERSION = PREFIX + "expected-version";

    private final SystemSettingRepository repository;
    private final FlinkClusterService flinkClusterService;

    @PostConstruct
    public void loadPersistedSettings() {
        List<SystemSetting> settings = repository.findBySettingKeyStartingWith(PREFIX);
        if (settings.isEmpty()) {
            return;
        }
        Map<String, String> values = settings.stream().collect(Collectors.toMap(
                SystemSetting::getSettingKey,
                SystemSetting::getSettingValue,
                (left, right) -> right
        ));
        flinkClusterService.updateRuntimeConfig(
                values.getOrDefault(REST_URL, flinkClusterService.getFlinkRestUrl()),
                values.getOrDefault(SUBMISSION_MODE, flinkClusterService.getSubmissionMode()),
                values.getOrDefault(SAVEPOINT_DIR, flinkClusterService.getSavepointDir()),
                Boolean.parseBoolean(values.getOrDefault(SQL_GATEWAY_ENABLED,
                        String.valueOf(flinkClusterService.isSqlGatewayEnabled()))),
                values.getOrDefault(SQL_GATEWAY_URL, flinkClusterService.getSqlGatewayUrl())
        );
        log.info("Loaded persisted Flink settings from management database");
    }

    public Map<String, Object> getFlinkConfig() {
        List<SystemSetting> settings;
        String loadError = null;
        try {
            settings = repository.findBySettingKeyStartingWith(PREFIX);
        } catch (RuntimeException exception) {
            log.warn("Unable to read persisted Flink settings, using runtime defaults: {}", exception.getMessage());
            settings = List.of();
            loadError = "持久化配置读取失败，当前显示部署环境配置";
        }
        Map<String, SystemSetting> byKey = settings.stream().collect(Collectors.toMap(
                SystemSetting::getSettingKey,
                Function.identity(),
                (left, right) -> right
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restApiUrl", flinkClusterService.getFlinkRestUrl());
        result.put("submissionMode", flinkClusterService.getSubmissionMode());
        result.put("savepointDir", flinkClusterService.getSavepointDir());
        result.put("sqlGatewayEnabled", flinkClusterService.isSqlGatewayEnabled());
        result.put("sqlGatewayUrl", flinkClusterService.getSqlGatewayUrl());
        result.put("flinkVersion", valueOf(byKey, EXPECTED_VERSION, ""));
        result.put("source", settings.isEmpty() ? "environment" : "database");
        if (loadError != null) {
            result.put("loadError", loadError);
        }

        settings.stream()
                .map(SystemSetting::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .ifPresent(value -> result.put("updatedAt", value.toString()));
        settings.stream()
                .filter(setting -> setting.getUpdatedBy() != null && !setting.getUpdatedBy().isBlank())
                .max((left, right) -> compareNullable(left.getUpdatedAt(), right.getUpdatedAt()))
                .ifPresent(value -> result.put("updatedBy", value.getUpdatedBy()));
        return result;
    }

    @Transactional
    public Map<String, Object> updateFlinkConfig(Map<String, Object> body, String username) {
        NormalizedConfig config = normalizeAndValidate(body);
        save(REST_URL, config.restApiUrl(), "Flink REST API 地址", username);
        save(SUBMISSION_MODE, config.submissionMode(), "Flink 作业提交模式", username);
        save(SAVEPOINT_DIR, config.savepointDir(), "Savepoint 存储目录", username);
        save(SQL_GATEWAY_ENABLED, String.valueOf(config.sqlGatewayEnabled()), "SQL Gateway 开关", username);
        save(SQL_GATEWAY_URL, config.sqlGatewayUrl(), "SQL Gateway 地址", username);
        save(EXPECTED_VERSION, config.flinkVersion(), "期望的 Flink 版本", username);

        flinkClusterService.updateRuntimeConfig(
                config.restApiUrl(),
                config.submissionMode(),
                config.savepointDir(),
                config.sqlGatewayEnabled(),
                config.sqlGatewayUrl()
        );
        return getFlinkConfig();
    }

    public Map<String, Object> testFlinkConfig(Map<String, Object> body) {
        NormalizedConfig config = normalizeAndValidate(body);
        Map<String, Object> result = new LinkedHashMap<>(
                flinkClusterService.healthCheck(config.restApiUrl())
        );
        String actualVersion = String.valueOf(result.getOrDefault("flinkVersion", ""));
        if (!config.flinkVersion().isBlank() && !actualVersion.isBlank()) {
            result.put("versionMatch", actualVersion.startsWith(config.flinkVersion()));
            result.put("expectedVersion", config.flinkVersion());
        }
        return result;
    }

    private NormalizedConfig normalizeAndValidate(Map<String, Object> body) {
        String restApiUrl = trimToEmpty(body.get("restApiUrl")).replaceAll("/+$", "");
        validateHttpUrl(restApiUrl, "Flink REST API 地址");

        String submissionMode = defaultIfBlank(trimToEmpty(body.get("submissionMode")), "application");
        if (!"application".equals(submissionMode) && !"session".equals(submissionMode)) {
            throw new IllegalArgumentException("提交模式仅支持 application 或 session");
        }

        String savepointDir = defaultIfBlank(trimToEmpty(body.get("savepointDir")), "file:///tmp/flink-savepoints");
        if (!savepointDir.startsWith("/") && !savepointDir.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.+")) {
            throw new IllegalArgumentException("Savepoint 目录必须是绝对路径或有效的存储 URI");
        }

        boolean sqlGatewayEnabled = Boolean.parseBoolean(trimToEmpty(body.get("sqlGatewayEnabled")));
        String sqlGatewayUrl = defaultIfBlank(trimToEmpty(body.get("sqlGatewayUrl")), "http://localhost:9083");
        if (sqlGatewayEnabled) {
            validateHttpUrl(sqlGatewayUrl, "SQL Gateway 地址");
        }

        return new NormalizedConfig(
                restApiUrl,
                submissionMode,
                savepointDir,
                sqlGatewayEnabled,
                sqlGatewayUrl.replaceAll("/+$", ""),
                trimToEmpty(body.get("flinkVersion"))
        );
    }

    private void validateHttpUrl(String value, String label) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(label + "必须是有效的 HTTP(S) 地址");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + "必须是有效的 HTTP(S) 地址");
        }
    }

    private void save(String key, String value, String description, String username) {
        SystemSetting setting = repository.findBySettingKey(key).orElseGet(SystemSetting::new);
        setting.setSettingKey(key);
        setting.setSettingValue(value == null ? "" : value);
        setting.setDescription(description);
        setting.setUpdatedBy(username);
        repository.save(setting);
    }

    private String valueOf(Map<String, SystemSetting> values, String key, String fallback) {
        SystemSetting setting = values.get(key);
        return setting == null ? fallback : setting.getSettingValue();
    }

    private int compareNullable(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record NormalizedConfig(
            String restApiUrl,
            String submissionMode,
            String savepointDir,
            boolean sqlGatewayEnabled,
            String sqlGatewayUrl,
            String flinkVersion
    ) {}
}
