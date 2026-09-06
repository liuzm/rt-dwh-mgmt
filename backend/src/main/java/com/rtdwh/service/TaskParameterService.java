package com.rtdwh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Task placeholders are values, never SQL fragments or identifiers. */
@Service @RequiredArgsConstructor
public class TaskParameterService {
    private final ObjectMapper mapper;
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]{0,63})}");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "date", "datetime");

    public String validateTemplate(String sql, String schema) {
        Map<String, JsonNode> definitions = definitions(schema == null ? "[]" : schema);
        Set<String> used = new HashSet<>();
        scan(sql, name -> { used.add(name); return "NULL"; });
        for (String name : used) if (!name.equals("bizdate") && !definitions.containsKey(name))
            throw new IllegalArgumentException("SQL 参数未声明: " + name);
        for (var entry : definitions.entrySet()) {
            if (!used.contains(entry.getKey())) throw new IllegalArgumentException("参数未在 SQL 中使用: " + entry.getKey());
            if (entry.getValue().hasNonNull("defaultValue")) literal(entry.getValue().path("type").asText(), entry.getValue().get("defaultValue"));
        }
        try { return mapper.writeValueAsString(definitions.values()); }
        catch (Exception e) { throw new IllegalArgumentException("参数契约无法保存"); }
    }

    public String normalize(String schema, String supplied) {
        ObjectNode values;
        try {
            JsonNode node = mapper.readTree(supplied == null || supplied.isBlank() ? "{}" : supplied);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            values = (ObjectNode) node;
        } catch (Exception e) { throw new IllegalArgumentException("运行参数必须是 JSON 对象"); }
        if (values.has("bizdate")) throw new IllegalArgumentException("bizdate 由业务日期提供，不能覆盖");
        Map<String, JsonNode> definitions = definitions(schema);
        if (schema == null) { // Legacy versions: retain scalar values, with safe literal rendering.
            values.forEach(value -> literal(infer(value), value));
            return values.toString();
        }
        values.fieldNames().forEachRemaining(name -> {
            if (!definitions.containsKey(name)) throw new IllegalArgumentException("包含未声明的运行参数: " + name);
        });
        ObjectNode result = mapper.createObjectNode();
        definitions.forEach((name, definition) -> {
            JsonNode value = values.has(name) ? values.get(name) : definition.get("defaultValue");
            if ((value == null || value.isNull()) && definition.path("required").asBoolean())
                throw new IllegalArgumentException("缺少必填运行参数: " + name);
            literal(definition.path("type").asText(), value);
            if (value == null) result.putNull(name); else result.set(name, value);
        });
        return result.toString();
    }

    public String render(String sql, String schema, String supplied, LocalDate businessDate) {
        try {
            JsonNode values = mapper.readTree(normalize(schema, supplied));
            Map<String, JsonNode> definitions = definitions(schema);
            return scan(sql, name -> {
                if (name.equals("bizdate")) {
                    if (businessDate == null) throw new IllegalArgumentException("缺少业务日期");
                    return quote(businessDate.toString());
                }
                if (!values.has(name)) throw new IllegalArgumentException("Flink SQL 存在未赋值参数: " + name);
                String type = schema == null ? infer(values.get(name)) : definitions.get(name).path("type").asText();
                return literal(type, values.get(name));
            });
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("参数契约无法解析", e); }
    }

    public String forAccessCheck(String sql) { return scan(sql, name -> "NULL"); }

    private Map<String, JsonNode> definitions(String schema) {
        if (schema == null) return Map.of();
        try {
            JsonNode root = mapper.readTree(schema);
            if (root == null || !root.isArray() || root.size() > 64) throw new IllegalArgumentException("参数契约必须是数组，最多 64 项");
            Map<String, JsonNode> result = new LinkedHashMap<>();
            for (JsonNode item : root) {
                String name = item.path("name").asText();
                if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || name.equals("bizdate")) throw new IllegalArgumentException("参数名称无效或使用了保留名");
                if (!TYPES.contains(item.path("type").asText())) throw new IllegalArgumentException("参数类型不支持");
                if (item.has("required") && !item.get("required").isBoolean()) throw new IllegalArgumentException("required 必须是布尔值");
                if (result.put(name, item) != null) throw new IllegalArgumentException("参数名称重复: " + name);
            }
            return result;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("参数契约 JSON 格式不正确"); }
    }

    private String infer(JsonNode value) {
        if (value == null || value.isNull() || value.isTextual()) return "string";
        if (value.isBoolean()) return "boolean";
        if (value.isNumber()) return "number";
        throw new IllegalArgumentException("运行参数仅支持标量值");
    }

    private String literal(String type, JsonNode value) {
        if (value == null || value.isNull()) return "NULL";
        try {
            return switch (type) {
                case "integer", "number" -> {
                    if (!value.isNumber()) throw new IllegalArgumentException();
                    BigDecimal number = value.decimalValue().stripTrailingZeros();
                    if (number.precision() > 38 || Math.abs(number.scale()) > 38 || (type.equals("integer") && number.scale() > 0)) throw new IllegalArgumentException();
                    yield number.toPlainString();
                }
                case "boolean" -> { if (!value.isBoolean()) throw new IllegalArgumentException(); yield value.asBoolean() ? "TRUE" : "FALSE"; }
                case "date" -> { if (!value.isTextual()) throw new IllegalArgumentException(); yield quote(LocalDate.parse(value.asText()).toString()); }
                case "datetime" -> { if (!value.isTextual()) throw new IllegalArgumentException(); yield quote(LocalDateTime.parse(value.asText().replace(' ', 'T')).toString().replace('T', ' ')); }
                default -> { if (!value.isTextual()) throw new IllegalArgumentException(); yield quote(value.asText()); }
            };
        } catch (RuntimeException e) { throw new IllegalArgumentException("运行参数与声明类型不符: " + type); }
    }
    private String quote(String text) {
        if (text.length() > 4096 || text.indexOf('\\') >= 0 || text.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("字符串参数过长或包含不支持的控制字符／反斜线");
        return "'" + text.replace("'", "''") + "'";
    }

    private String scan(String sql, java.util.function.Function<String, String> replacement) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("任务未配置 Flink SQL");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int start = i++;
                boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == c) {
                        if (i < sql.length() && sql.charAt(i) == c) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("SQL 引号未闭合");
                String block = sql.substring(start, i);
                var token = TOKEN.matcher(block.substring(1, block.length() - 1));
                if (c == '\'' && token.matches()) result.append(replacement.apply(token.group(1)));
                else { if (block.contains("${")) throw new IllegalArgumentException("参数必须是完整值，不能拼接字符串或标识符"); result.append(block); }
            } else if (sql.startsWith("--", i) || sql.startsWith("/*", i)) {
                int end = sql.startsWith("--", i) ? sql.indexOf('\n', i) : sql.indexOf("*/", i + 2);
                if (end < 0) end = sql.length(); else if (sql.startsWith("/*", i)) end += 2;
                String block = sql.substring(i, end);
                if (block.contains("${")) throw new IllegalArgumentException("注释中不能使用运行参数");
                result.append(block); i = end;
            } else if (sql.startsWith("${", i)) {
                var token = TOKEN.matcher(sql); token.region(i, sql.length());
                if (!token.lookingAt()) throw new IllegalArgumentException("参数占位符格式不正确");
                int end = token.end();
                if ((i > 0 && Character.isJavaIdentifierPart(sql.charAt(i - 1))) || (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))))
                    throw new IllegalArgumentException("参数不能拼接标识符");
                result.append(replacement.apply(token.group(1))); i = end;
            } else { result.append(c); i++; }
        }
        return result.toString();
    }
}
