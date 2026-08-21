package com.rtdwh.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SystemHealthStatus;
import com.rtdwh.repository.SystemHealthStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthStatusService {

    private static final String LATEST_STATUS_KEY = "latest";
    private static final TypeReference<LinkedHashMap<String, Object>> STATUS_TYPE = new TypeReference<>() { };

    private final HealthCheckService healthCheckService;
    private final SystemHealthStatusRepository repository;
    private final ObjectMapper objectMapper;

    /** Returns the most recently persisted result without probing external dependencies. */
    public Map<String, Object> getLatest() {
        return repository.findById(LATEST_STATUS_KEY)
                .map(this::deserialize)
                .orElseGet(this::emptyStatus);
    }

    /** Runs and persists a complete health check. Synchronized to avoid overlapping scheduled/manual writes. */
    public synchronized Map<String, Object> refreshAll(String source) {
        Map<String, Object> result = new LinkedHashMap<>(healthCheckService.checkAll());
        result.put("source", normalizeSource(source));
        persist(result, normalizeSource(source));
        return result;
    }

    /** Runs one check, merges it into the last snapshot and persists the resulting complete status. */
    public synchronized Map<String, Object> refreshComponent(String component, String source) {
        String normalizedComponent = component.toLowerCase(Locale.ROOT);
        long startedAt = System.currentTimeMillis();
        Map<String, Object> componentResult = healthCheckService.checkComponent(normalizedComponent);
        Map<String, Object> result = new LinkedHashMap<>(getLatest());
        result.put(normalizedComponent, componentResult);

        Map<String, Object> flink = componentMap(result, "flink");
        Map<String, Object> paimon = componentMap(result, "paimon");
        Map<String, Object> mysql = componentMap(result, "mysql");
        result.put("overall", healthCheckService.determineOverallStatus(flink, paimon, mysql));
        result.put("checkedAt", Instant.now().toString());
        result.put("durationMs", System.currentTimeMillis() - startedAt);
        result.put("source", normalizeSource(source));
        result.put("lastCheckedComponent", normalizedComponent);
        persist(result, normalizeSource(source));
        return result;
    }

    private void persist(Map<String, Object> result, String source) {
        try {
            SystemHealthStatus entity = repository.findById(LATEST_STATUS_KEY)
                    .orElseGet(() -> SystemHealthStatus.builder().statusKey(LATEST_STATUS_KEY).build());
            entity.setOverallStatus(String.valueOf(result.getOrDefault("overall", "unknown")));
            entity.setPayloadJson(objectMapper.writeValueAsString(result));
            entity.setCheckedAt(LocalDateTime.now());
            entity.setDurationMs(numberValue(result.get("durationMs")));
            entity.setCheckSource(source);
            repository.saveAndFlush(entity);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("健康状态序列化失败", exception);
        }
    }

    private Map<String, Object> deserialize(SystemHealthStatus entity) {
        try {
            Map<String, Object> result = objectMapper.readValue(entity.getPayloadJson(), STATUS_TYPE);
            result.putIfAbsent("source", entity.getCheckSource());
            return result;
        } catch (JsonProcessingException exception) {
            log.error("Failed to deserialize persisted system health status", exception);
            Map<String, Object> result = emptyStatus();
            result.put("storageError", "最近一次健康状态无法解析");
            return result;
        }
    }

    private Map<String, Object> emptyStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overall", "unknown");
        result.put("flink", unknownComponent());
        result.put("paimon", unknownComponent());
        result.put("mysql", unknownComponent());
        result.put("durationMs", 0L);
        result.put("source", "none");
        return result;
    }

    private Map<String, Object> unknownComponent() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "unknown");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> componentMap(Map<String, Object> status, String component) {
        Object value = status.get(component);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> unknown = unknownComponent();
        status.put(component, unknown);
        return unknown;
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String normalizeSource(String source) {
        return source == null || source.isBlank() ? "manual" : source;
    }
}
