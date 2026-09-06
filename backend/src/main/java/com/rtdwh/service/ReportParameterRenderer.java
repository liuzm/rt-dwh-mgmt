package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReportParameterRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]{0,63})\\s*}}");
    private static final Set<String> TYPES = Set.of("string", "number", "boolean", "date", "datetime", "stringlist");
    private static final DateTimeFormatter SQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    public String render(String sql, String filterConfig, Map<String, Object> supplied) {
        Map<String, Definition> definitions = definitions(filterConfig);
        Set<String> placeholders = placeholders(sql);
        validateContract(definitions, placeholders);

        Map<String, Object> parameters = supplied == null ? Map.of() : supplied;
        Set<String> unknown = new HashSet<>(parameters.keySet());
        unknown.removeAll(definitions.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("包含未声明的报表参数: " + String.join(", ", unknown));
        }

        Matcher matcher = PLACEHOLDER.matcher(sql);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Definition definition = definitions.get(matcher.group(1));
            Object value = parameters.containsKey(definition.name())
                    ? parameters.get(definition.name())
                    : definition.defaultValue();
            if (definition.required() && isMissing(value)) {
                throw new IllegalArgumentException("缺少必填报表参数: " + definition.name());
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(literal(definition, value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    public String sqlForAccessCheck(String sql, String filterConfig) {
        validateTemplate(sql, filterConfig);
        Map<String, Definition> definitions = definitions(filterConfig);
        return PLACEHOLDER.matcher(sql).replaceAll(match ->
                "stringlist".equals(definitions.get(match.group(1)).type()) ? "(NULL)" : "NULL");
    }

    /** Representative values for LIMIT 0 metadata inspection; never a saved runtime default. */
    public String sqlForContractCheck(String sql, String filterConfig) {
        Map<String, Object> samples = new LinkedHashMap<>();
        for (Definition definition : definitions(filterConfig).values()) {
            Object sample = definition.defaultValue();
            if (sample == null) sample = switch (definition.type()) {
                case "number" -> 0;
                case "boolean" -> false;
                case "date" -> "2000-01-01";
                case "datetime" -> "2000-01-01T00:00:00";
                case "stringlist" -> List.of("contract");
                default -> "contract";
            };
            samples.put(definition.name(), sample);
        }
        return render(sql, filterConfig, samples);
    }

    public void validateTemplate(String sql, String filterConfig) {
        Map<String, Definition> definitions = definitions(filterConfig);
        validateContract(definitions, placeholders(sql));
        for (Definition definition : definitions.values()) {
            if (definition.defaultValue() != null) literal(definition, definition.defaultValue());
        }
    }

    private void validateContract(Map<String, Definition> definitions, Set<String> placeholders) {
        Set<String> undeclared = new HashSet<>(placeholders);
        undeclared.removeAll(definitions.keySet());
        if (!undeclared.isEmpty()) {
            throw new IllegalArgumentException("SQL 使用了未声明的报表参数: " + String.join(", ", undeclared));
        }
        Set<String> unused = new HashSet<>(definitions.keySet());
        unused.removeAll(placeholders);
        if (!unused.isEmpty()) {
            throw new IllegalArgumentException("报表参数未在 SQL 中使用: " + String.join(", ", unused));
        }
    }

    private Set<String> placeholders(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("报表 SQL 不能为空");
        Set<String> result = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) result.add(matcher.group(1));
        String remainder = matcher.replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}")) {
            throw new IllegalArgumentException("报表参数占位符格式不正确，应使用 {{parameter_name}}");
        }
        return result;
    }

    private Map<String, Definition> definitions(String filterConfig) {
        if (filterConfig == null || filterConfig.isBlank()) return Map.of();
        try {
            JsonNode root = objectMapper.readTree(filterConfig);
            JsonNode array = root.isArray() ? root : root.path("parameters");
            if (!array.isArray()) throw new IllegalArgumentException("筛选参数配置必须是数组或包含 parameters 数组");
            Map<String, Definition> definitions = new LinkedHashMap<>();
            for (JsonNode item : array) {
                String name = item.path("name").asText("").trim();
                if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                    throw new IllegalArgumentException("报表参数名不合法: " + name);
                }
                String type = item.path("type").asText("string").trim().toLowerCase(Locale.ROOT);
                if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的报表参数类型: " + type);
                Object defaultValue = item.has("defaultValue") && !item.get("defaultValue").isNull()
                        ? objectMapper.convertValue(item.get("defaultValue"), Object.class) : null;
                Definition previous = definitions.put(name,
                        new Definition(name, type, item.path("required").asBoolean(false), defaultValue));
                if (previous != null) throw new IllegalArgumentException("报表参数重复定义: " + name);
            }
            return definitions;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("筛选参数配置 JSON 格式不正确");
        }
    }

    private String literal(Definition definition, Object value) {
        if (value == null) return "NULL";
        return switch (definition.type()) {
            case "number" -> number(definition.name(), value);
            case "boolean" -> bool(definition.name(), value);
            case "date" -> quote(date(definition.name(), value));
            case "datetime" -> quote(dateTime(definition.name(), value));
            case "stringlist" -> stringList(definition.name(), value);
            default -> quote(text(definition.name(), value));
        };
    }

    private String number(String name, Object value) {
        try {
            return new BigDecimal(String.valueOf(value).trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("报表参数 " + name + " 必须是数字");
        }
    }

    private String bool(String name, Object value) {
        if (value instanceof Boolean bool) return bool ? "TRUE" : "FALSE";
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (Set.of("true", "1").contains(text)) return "TRUE";
        if (Set.of("false", "0").contains(text)) return "FALSE";
        throw new IllegalArgumentException("报表参数 " + name + " 必须是布尔值");
    }

    private String date(String name, Object value) {
        try {
            return LocalDate.parse(String.valueOf(value).trim()).toString();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("报表参数 " + name + " 必须是 yyyy-MM-dd 日期");
        }
    }

    private String dateTime(String name, Object value) {
        String text = String.valueOf(value).trim();
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).format(SQL_DATETIME);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toLocalDateTime().format(SQL_DATETIME);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("报表参数 " + name + " 必须是 ISO 日期时间");
            }
        }
    }

    private String stringList(String name, Object value) {
        List<?> values;
        if (value instanceof Collection<?> collection) values = new ArrayList<>(collection);
        else values = List.of(String.valueOf(value).split(",", -1));
        if (values.size() > 200) throw new IllegalArgumentException("报表参数 " + name + " 最多允许 200 项");
        List<String> literals = values.stream().map(item -> quote(text(name, item))).toList();
        return literals.isEmpty() ? "(NULL)" : "(" + String.join(",", literals) + ")";
    }

    private String text(String name, Object value) {
        String text = String.valueOf(value);
        if (text.length() > 4096) throw new IllegalArgumentException("报表参数 " + name + " 不能超过 4096 个字符");
        return text;
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private record Definition(String name, String type, boolean required, Object defaultValue) {}
}
