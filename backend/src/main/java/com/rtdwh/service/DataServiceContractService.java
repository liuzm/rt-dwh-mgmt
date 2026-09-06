package com.rtdwh.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.DataServiceDefinition;
import com.rtdwh.entity.DataServiceVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class DataServiceContractService {
    private final DorisConnectionService doris;
    private final ReportParameterRenderer renderer;
    private final QueryAccessScopeService access;
    private final ViewSqlService sqlParser;
    private final ObjectMapper mapper;

    public record Column(String name, String type, int precision, int scale, boolean nullable) {}
    public record Inspection(List<Column> columns, List<ViewSqlService.Name> dependencies) {}

    public Inspection inspect(DataServiceDefinition definition, Long actor) {
        access.assertDataServiceExecutionIdentity(definition.getCreatorId());
        String check = renderer.sqlForAccessCheck(definition.getSqlTemplate(), definition.getParameterConfig());
        access.validateDoris(actor, check, definition.getCatalogName(), definition.getDatabaseName());
        access.validateDoris(definition.getCreatorId(), check, definition.getCatalogName(), definition.getDatabaseName());
        var parsed = sqlParser.parse(check, definition.getCatalogName(), definition.getDatabaseName(), false);
        String sample = renderer.sqlForContractCheck(definition.getSqlTemplate(), definition.getParameterConfig());
        String sql = sqlParser.parse(sample, definition.getCatalogName(), definition.getDatabaseName(), false).sql();
        try (Connection connection = doris.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("SWITCH " + DorisConnectionService.quoteIdentifier(definition.getCatalogName()));
            statement.execute("USE " + DorisConnectionService.quoteIdentifier(definition.getDatabaseName()));
            statement.execute("SET query_timeout = 15");
            statement.execute("SET exec_mem_limit = " + doris.getExecMemLimitBytes());
            try (ResultSet rs = statement.executeQuery("SELECT * FROM (" + sql + ") AS api_contract LIMIT 0")) {
                List<Column> columns = readColumns(rs.getMetaData());
                Set<String> names = new HashSet<>();
                if (columns.isEmpty()) throw new IllegalArgumentException("数据 API 必须返回明确的结果列");
                for (Column column : columns) if (!names.add(column.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("API 输出列名重复，请使用明确的别名: " + column.name());
                }
                return new Inspection(columns, parsed.dependencies());
            }
        } catch (SQLException exception) {
            throw new IllegalArgumentException("API 结果契约校验失败: " + exception.getMessage(), exception);
        }
    }

    public static List<Column> readColumns(ResultSetMetaData metadata) throws SQLException {
        List<Column> result = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            result.add(new Column(metadata.getColumnLabel(i), String.valueOf(metadata.getColumnTypeName(i)).toUpperCase(Locale.ROOT),
                    metadata.getPrecision(i), metadata.getScale(i), metadata.isNullable(i) != ResultSetMetaData.columnNoNulls));
        }
        return result;
    }

    public List<String> breakingChanges(DataServiceVersion current, DataServiceDefinition candidate,
                                         List<Column> previousColumns, List<Column> columns) {
        List<String> changes = new ArrayList<>();
        if (!compatibleColumns(previousColumns, columns)) changes.add("输出列名称、顺序、类型或可空性不兼容，请使用新服务编码迁移");
        Map<String, JsonNode> oldParams = parameters(current.getParameterConfig());
        Map<String, JsonNode> newParams = parameters(candidate.getParameterConfig());
        for (var entry : oldParams.entrySet()) {
            JsonNode next = newParams.get(entry.getKey());
            if (next == null || !type(entry.getValue()).equals(type(next))) {
                changes.add("参数删除或类型改变: " + entry.getKey());
            } else if (!entry.getValue().path("required").asBoolean(false) && next.path("required").asBoolean(false)) {
                changes.add("可选参数改为必填: " + entry.getKey());
            } else if (next.path("required").asBoolean(false) && hasUsableDefault(entry.getValue()) && !hasUsableDefault(next)) {
                changes.add("必填参数移除有效默认值: " + entry.getKey());
            }
        }
        newParams.forEach((name, value) -> {
            if (!oldParams.containsKey(name) && value.path("required").asBoolean(false) && !hasUsableDefault(value)) {
                changes.add("新增无默认值的必填参数: " + name);
            }
        });
        return changes;
    }

    public void validateResult(DataServiceVersion version, Object actualSchema) {
        if (version.getResultColumnsJson() == null) {
            if (!"legacy_capture".equals(version.getOrigin())) throw new IllegalStateException("发布版本缺少结果契约");
            return;
        }
        List<Column> expected = columns(version.getResultColumnsJson());
        List<Column> actual = mapper.convertValue(actualSchema, new TypeReference<List<Column>>() {});
        if (!compatibleColumns(expected, actual)) {
            throw new IllegalStateException("API 实际结果与发布契约不一致，已停止返回数据；参数用于输出表达式时请显式 CAST 固定类型");
        }
    }

    private boolean compatibleColumns(List<Column> expected, List<Column> actual) {
        if (expected == null || actual == null || expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            Column a = expected.get(i), b = actual.get(i);
            if (!Objects.equals(a.name(), b.name()) || !Objects.equals(a.type(), b.type())
                    || a.precision() != b.precision() || a.scale() != b.scale() || !a.nullable() && b.nullable()) return false;
        }
        return true;
    }

    public List<Column> columns(String json) {
        try { return mapper.readValue(json, new TypeReference<List<Column>>() {}); }
        catch (Exception exception) { throw new IllegalStateException("发布结果契约无法读取", exception); }
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("API 契约无法保存", exception); }
    }
    public boolean sameJson(String left, String right) {
        try { return Objects.equals(mapper.readTree(left == null ? "null" : left), mapper.readTree(right == null ? "null" : right)); }
        catch (Exception exception) { return false; }
    }
    private Map<String, JsonNode> parameters(String config) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (config == null || config.isBlank()) return result;
        try {
            JsonNode root = mapper.readTree(config), array = root.isArray() ? root : root.path("parameters");
            if (!array.isArray()) throw new IllegalArgumentException("参数契约格式错误");
            for (JsonNode node : array) result.put(node.path("name").asText(), node);
            return result;
        } catch (Exception exception) { throw new IllegalArgumentException("参数契约无法读取", exception); }
    }
    private String type(JsonNode definition) { return definition.path("type").asText("string").toLowerCase(Locale.ROOT); }
    private boolean hasUsableDefault(JsonNode definition) {
        JsonNode value = definition.path("defaultValue");
        return !value.isMissingNode() && !value.isNull()
                && !(value.isTextual() && value.asText().isBlank()) && !(value.isArray() && value.isEmpty());
    }
}
