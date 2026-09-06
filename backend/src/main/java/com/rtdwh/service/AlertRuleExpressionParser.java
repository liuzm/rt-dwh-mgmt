package com.rtdwh.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AlertRuleExpressionParser {
    private static final Pattern DELAY = Pattern.compile(
            "(?i)^\\s*(?:lag(?:_ms)?\\s*>\\s*)?(\\d+(?:\\.\\d+)?)\\s*(ms|s|m)?\\s*$");

    private AlertRuleExpressionParser() {
    }

    static long delayThresholdMs(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("延迟告警必须配置阈值，例如 5000ms、30s 或 lag_ms > 60000");
        }
        Matcher matcher = DELAY.matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("延迟阈值格式不正确，支持 5000ms、30s、2m 或 lag_ms > 60000");
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "ms" : matcher.group(2).toLowerCase(Locale.ROOT);
        double multiplier = switch (unit) {
            case "s" -> 1_000D;
            case "m" -> 60_000D;
            default -> 1D;
        };
        long result = Math.round(value * multiplier);
        if (result <= 0) throw new IllegalArgumentException("延迟阈值必须大于 0");
        return result;
    }
}
