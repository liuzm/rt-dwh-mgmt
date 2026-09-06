package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.*;

/** Frozen non-secret runtime configuration. A changed environment requires republishing. */
@Service @RequiredArgsConstructor
public class RuntimeEnvironmentService {
    private final Environment environment;
    private final ObjectMapper mapper;
    private static final List<String> KEYS = List.of("flink.rest-api.url", "flink.sql-gateway.url", "flink.sql-gateway.enabled",
            "flink.submission.savepoint-dir", "paimon.warehouse-path", "paimon.metastore", "paimon.jdbc-uri",
            "paimon.jdbc-user", "paimon.catalog-key", "doris.jdbc-url", "doris.catalog", "doris.database",
            "doris.paimon-warehouse-path", "doris.paimon-jdbc-uri");

    public void freeze(SyncTask definition) {
        // SQL-managed secrets cannot be preserved in a publicly readable version snapshot.
        String sql = definition.getFlinkSql();
        if (sql != null && java.util.regex.Pattern.compile("(?i)'(?:[^']*(?:password|secret|token|access-key)[^']*)'\\s*=\\s*'(?!__RTDWH_)[^']+'")
                .matcher(sql).find()) throw new IllegalArgumentException("发布 SQL 不得内嵌凭证，请使用受控 CDC 数据源引用");
        definition.setRuntimeConfigJson(current(definition));
        definition.setRuntimeConfigHash(TaskReleaseContractService.fingerprint(definition.getRuntimeConfigJson(), "runtime-v1"));
    }

    public void validate(SyncTask definition) {
        if (definition.getRuntimeConfigHash() == null) return; // Historical releases are explicitly unverified.
        String current = current(definition);
        String hash = TaskReleaseContractService.fingerprint(current, "runtime-v1");
        if (!hash.equals(definition.getRuntimeConfigHash()) || !current.equals(definition.getRuntimeConfigJson()))
            throw new IllegalArgumentException("运行环境已变更，请核对配置并重新发布任务；原实例禁止切换环境执行");
    }

    private String current(SyncTask definition) {
        Map<String, Object> config = new TreeMap<>();
        KEYS.forEach(key -> {
            String value = environment.getProperty(key, "");
            if (key.contains("url") || key.contains("uri") || value.contains("://")) {
                // Do not persist embedded URL credentials or arbitrary query parameters.
                value = value.replaceAll("//[^/@]*@", "//[credential]@");
                String[] parts = value.split("\\?", 2);
                value = parts[0];
                if (parts.length == 2) {
                    List<String> options = new ArrayList<>();
                    for (String option : parts[1].split("&")) {
                        String[] pair = option.split("=", 2);
                        String keyName = pair[0].toLowerCase(Locale.ROOT);
                        Set<String> publicOptions = Set.of("useunicode", "characterencoding", "usessl", "requiressl",
                                "verifyservercertificate", "serverTimezone".toLowerCase(Locale.ROOT), "allowpublickeyretrieval",
                                "connecttimeout", "sockettimeout");
                        options.add(pair[0] + "=" + (publicOptions.contains(keyName) && pair.length == 2 ? pair[1] : "[configured]"));
                    }
                    Collections.sort(options);
                    value += "?" + String.join("&", options);
                }
            }
            config.put(key, value);
        });
        config.put("credentialRefs", definition.getSourceConfigId() == null
                ? List.of("config:paimon.jdbc-password")
                : List.of("datasource:" + definition.getSourceConfigId(), "config:paimon.jdbc-password"));
        try { return mapper.writeValueAsString(config); }
        catch (Exception e) { throw new IllegalStateException("运行环境快照无法生成"); }
    }
}
