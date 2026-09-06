package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ReportScheduleConfig(boolean enabled, String cron, ZoneId zoneId, int retainCount, int maxRows,
                            int maxRetries, String notifyOn, Set<String> notifyChannels, List<String> recipients,
                            Map<String, Object> parameters) {
    static ReportScheduleConfig parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return new ReportScheduleConfig(false, "0 0 * * * *", ZoneId.of("Asia/Shanghai"),
                    30, 1000, 0, "never", Set.of(), List.of(), Map.of());
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException("调度配置必须是 JSON 对象");
            boolean enabled = node.path("enabled").asBoolean(false);
            String cron = node.path("cron").asText("0 0 * * * *").trim();
            CronExpression.parse(cron);
            ZoneId zoneId = ZoneId.of(node.path("timezone").asText("Asia/Shanghai").trim());
            int retainCount = Math.max(1, Math.min(node.path("retainCount").asInt(30), 200));
            int maxRows = Math.max(1, Math.min(node.path("maxRows").asInt(1000), 5000));
            int maxRetries = Math.max(0, Math.min(node.path("maxRetries").asInt(0), 3));
            String notifyOn = node.path("notifyOn").asText("never").trim().toLowerCase();
            if (!Set.of("never", "success", "failure", "always").contains(notifyOn)) {
                throw new IllegalArgumentException("通知时机只能是 never、success、failure 或 always");
            }
            Set<String> channels = new java.util.HashSet<>();
            if (node.path("notifyChannels").isArray()) {
                node.path("notifyChannels").forEach(item -> channels.add(item.asText().trim().toLowerCase()));
            }
            if (!Set.of("email", "dingtalk", "wecom").containsAll(channels)) {
                throw new IllegalArgumentException("通知渠道仅支持 email、dingtalk、wecom");
            }
            List<String> recipients = new ArrayList<>();
            String recipientText = node.path("recipients").asText("");
            for (String recipient : recipientText.split("[,;]")) {
                String email = recipient.trim();
                if (!email.isBlank()) {
                    if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                        throw new IllegalArgumentException("订阅邮箱格式不正确: " + email);
                    }
                    recipients.add(email);
                }
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            JsonNode parameterNode = node.path("parameters");
            if (!parameterNode.isMissingNode() && !parameterNode.isObject()) {
                throw new IllegalArgumentException("调度参数 parameters 必须是 JSON 对象");
            }
            parameterNode.fields().forEachRemaining(entry ->
                    parameters.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
            return new ReportScheduleConfig(enabled, cron, zoneId, retainCount, maxRows,
                    maxRetries, notifyOn, Set.copyOf(channels), List.copyOf(recipients),
                    Collections.unmodifiableMap(parameters));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("调度配置 JSON 格式不正确");
        }
    }

    LocalDateTime nextAfter(LocalDateTime base) {
        ZonedDateTime zonedBase = base.atZone(zoneId);
        ZonedDateTime next = CronExpression.parse(cron).next(zonedBase);
        if (next == null) throw new IllegalArgumentException("Cron 无法计算下一次运行时间");
        return next.toLocalDateTime();
    }

    boolean shouldNotify(String status) {
        return !notifyChannels.isEmpty() && switch (notifyOn) {
            case "always" -> true;
            case "success" -> "success".equals(status);
            case "failure" -> "failed".equals(status);
            default -> false;
        };
    }
}
