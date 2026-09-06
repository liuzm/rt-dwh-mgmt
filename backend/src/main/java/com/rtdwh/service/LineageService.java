package com.rtdwh.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.dto.LineageGraphDTO;
import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DwhDataLineage;
import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.repository.DatasourceConfigRepository;
import com.rtdwh.repository.DwhDataLineageRepository;
import com.rtdwh.repository.DwhTableMetaRepository;
import com.rtdwh.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LineageService {
    private static final Pattern SOURCE_TABLE = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+(`?[A-Za-z_][A-Za-z0-9_]*`?(?:\\.`?[A-Za-z_][A-Za-z0-9_]*`?){0,2})");
    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+(`?[A-Za-z_][A-Za-z0-9_]*`?(?:\\.`?[A-Za-z_][A-Za-z0-9_]*`?){0,2})");

    private final SyncTaskRepository taskRepository;
    private final DatasourceConfigRepository datasourceRepository;
    private final DwhTableMetaRepository tableRepository;
    private final DwhDataLineageRepository lineageRepository;
    private final ObjectMapper objectMapper;
    private final QueryAccessScopeService accessScopeService;
    private final SyncTaskService syncTaskService;

    @org.springframework.beans.factory.annotation.Value("${doris.catalog:rtdwh_paimon}")
    private String platformCatalog = "rtdwh_paimon";

    @Transactional(readOnly = true)
    public LineageGraphDTO getGraph(String layer, String keyword, Long userId) {
        Map<String, LineageGraphDTO.Node> nodes = new LinkedHashMap<>();
        Map<String, LineageGraphDTO.Edge> edges = new LinkedHashMap<>();
        Map<Long, DatasourceConfig> datasources = new HashMap<>();
        datasourceRepository.findAll().forEach(item -> datasources.put(item.getId(), item));
        Map<String, DwhTableMeta> tables = new HashMap<>();
        tableRepository.findAll().stream().filter(this::isPlatformTable).filter(item -> accessScopeService.allowed(userId, platformCatalog,
                item.getPaimonDb(), item.getPaimonTable())).forEach(item -> {
            tables.put(tableKey(item.getPaimonDb(), item.getPaimonTable()), item);
            addDwhNode(nodes, item);
        });

        List<SyncTask> visibleTasks = syncTaskService.listTasksForUser(userId, null, null, null);
        Set<Long> visibleTaskIds = new HashSet<>();
        for (SyncTask task : visibleTasks) {
            visibleTaskIds.add(task.getId());
            String taskNodeId = "task:" + task.getId();
            nodes.put(taskNodeId, new LineageGraphDTO.Node(taskNodeId, task.getTaskName(), task.getTaskName(),
                    "task", null, task.getStatus().name(), Map.of("taskId", task.getId(), "taskType", task.getTaskType().name())));
            List<TableMapping> mappings = parseMappings(task.getTableMappings());
            if (mappings.isEmpty()) addSqlLineage(task, nodes, edges, taskNodeId, tables, userId);
            else addMappedLineage(task, mappings, datasources, nodes, edges, taskNodeId, tables);
        }

        for (DwhDataLineage lineage : lineageRepository.findAll()) {
            DwhTableMeta source = lineage.getSourceTable();
            DwhTableMeta target = lineage.getTargetTable();
            if (!isPlatformTable(source) || !isPlatformTable(target)) continue;
            if (!accessScopeService.allowed(userId, platformCatalog, source.getPaimonDb(), source.getPaimonTable())
                    || !accessScopeService.allowed(userId, platformCatalog, target.getPaimonDb(), target.getPaimonTable())) continue;
            if (lineage.getSyncTask() != null && !visibleTaskIds.contains(lineage.getSyncTask().getId())) continue;
            addDwhNode(nodes, source);
            addDwhNode(nodes, target);
            String sourceId = dwhNodeId(source.getPaimonDb(), source.getPaimonTable());
            String targetId = dwhNodeId(target.getPaimonDb(), target.getPaimonTable());
            addEdge(edges, sourceId, targetId, lineage.getLineageType().name(),
                    lineage.getLineageType().name(), lineage.getSyncTask() == null ? null : lineage.getSyncTask().getId());
        }

        return filteredGraph(nodes, edges, layer, keyword);
    }

    // The legacy graph has two-part Paimon keys. View evidence lives in the asset context.
    private boolean isPlatformTable(DwhTableMeta table) {
        return (table.getAssetType() == null || table.getAssetType().startsWith("paimon"))
                && (table.getCatalogName() == null || platformCatalog.equalsIgnoreCase(table.getCatalogName()));
    }

    private void addMappedLineage(SyncTask task, List<TableMapping> mappings,
                                  Map<Long, DatasourceConfig> datasources,
                                  Map<String, LineageGraphDTO.Node> nodes,
                                  Map<String, LineageGraphDTO.Edge> edges,
                                  String taskNodeId, Map<String, DwhTableMeta> tables) {
        DatasourceConfig sourceConfig = datasources.get(task.getSourceConfigId());
        String datasourceName = sourceConfig == null ? "数据源 #" + task.getSourceConfigId() : sourceConfig.getConfigName();
        String datasourceId = "datasource:" + task.getSourceConfigId();
        nodes.put(datasourceId, new LineageGraphDTO.Node(datasourceId, datasourceName, datasourceName,
                "datasource", null, null, sourceConfig == null ? Map.of() : Map.of(
                "dbType", sourceConfig.getDbType().name(), "database", sourceConfig.getDatabase())));
        for (TableMapping mapping : mappings) {
            if (blank(mapping.sourceTable()) || blank(mapping.targetTable())) continue;
            String sourceQualified = (sourceConfig == null || blank(sourceConfig.getDatabase()) ? "" : sourceConfig.getDatabase() + ".")
                    + mapping.sourceTable();
            String sourceId = "source-table:" + task.getSourceConfigId() + ":" + mapping.sourceTable();
            nodes.put(sourceId, new LineageGraphDTO.Node(sourceId, mapping.sourceTable(), sourceQualified,
                    "source_table", null, null, Map.of("datasourceId", task.getSourceConfigId())));
            addEdge(edges, datasourceId, sourceId, "contains", "包含", task.getId());
            addEdge(edges, sourceId, taskNodeId, "cdc_input", "CDC", task.getId());

            String targetDb = blank(mapping.targetDb()) ? "ods" : mapping.targetDb();
            String targetId = dwhNodeId(targetDb, mapping.targetTable());
            DwhTableMeta meta = tables.get(tableKey(targetDb, mapping.targetTable()));
            if (meta != null) addDwhNode(nodes, meta);
            else nodes.put(targetId, new LineageGraphDTO.Node(targetId, mapping.targetTable(),
                    targetDb + "." + mapping.targetTable(), "table", inferLayer(targetDb), null, Map.of()));
            addEdge(edges, taskNodeId, targetId, "task_output", mapping.syncMode(), task.getId());
        }
    }

    private void addSqlLineage(SyncTask task, Map<String, LineageGraphDTO.Node> nodes,
                               Map<String, LineageGraphDTO.Edge> edges, String taskNodeId,
                               Map<String, DwhTableMeta> tables, Long userId) {
        String sql = task.getFlinkSql();
        if (blank(sql)) return;
        Matcher sources = SOURCE_TABLE.matcher(sql);
        while (sources.find()) {
            if (!accessScopeService.allowedReference(userId, sources.group(1), platformCatalog, "ods")) continue;
            TableRef ref = tableRef(sources.group(1));
            String id = dwhNodeId(ref.database(), ref.table());
            addTableRef(nodes, tables, ref, id);
            addEdge(edges, id, taskNodeId, "sql_input", "SQL 输入", task.getId());
        }
        Matcher target = TARGET_TABLE.matcher(sql);
        if (target.find() && accessScopeService.allowedReference(userId, target.group(1), platformCatalog, "ods")) {
            TableRef ref = tableRef(target.group(1));
            String id = dwhNodeId(ref.database(), ref.table());
            addTableRef(nodes, tables, ref, id);
            addEdge(edges, taskNodeId, id, "sql_output", "SQL 输出", task.getId());
        }
    }

    private void addTableRef(Map<String, LineageGraphDTO.Node> nodes, Map<String, DwhTableMeta> tables,
                             TableRef ref, String id) {
        DwhTableMeta meta = tables.get(tableKey(ref.database(), ref.table()));
        if (meta != null) addDwhNode(nodes, meta);
        else nodes.putIfAbsent(id, new LineageGraphDTO.Node(id, ref.table(), ref.database() + "." + ref.table(),
                "table", inferLayer(ref.database()), null, Map.of("discoveredFrom", "sql")));
    }

    private LineageGraphDTO filteredGraph(Map<String, LineageGraphDTO.Node> nodes,
                                           Map<String, LineageGraphDTO.Edge> edges,
                                           String layer, String keyword) {
        Set<String> selected = new LinkedHashSet<>();
        for (LineageGraphDTO.Node node : nodes.values()) {
            boolean layerMatch = blank(layer) || layer.equalsIgnoreCase(node.layer());
            boolean keywordMatch = blank(keyword) || (node.name() + " " + node.qualifiedName())
                    .toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
            if (layerMatch && keywordMatch) selected.add(node.id());
        }
        if (!blank(layer) || !blank(keyword)) {
            for (LineageGraphDTO.Edge edge : edges.values()) {
                if (selected.contains(edge.source())) selected.add(edge.target());
                if (selected.contains(edge.target())) selected.add(edge.source());
            }
        }
        List<LineageGraphDTO.Node> filteredNodes = nodes.values().stream()
                .filter(node -> selected.contains(node.id())).toList();
        Set<String> ids = new HashSet<>(selected);
        List<LineageGraphDTO.Edge> filteredEdges = edges.values().stream()
                .filter(edge -> ids.contains(edge.source()) && ids.contains(edge.target())).toList();
        return new LineageGraphDTO(filteredNodes, filteredEdges);
    }

    private void addDwhNode(Map<String, LineageGraphDTO.Node> nodes, DwhTableMeta table) {
        String id = dwhNodeId(table.getPaimonDb(), table.getPaimonTable());
        nodes.put(id, new LineageGraphDTO.Node(id, table.getPaimonTable(),
                table.getPaimonDb() + "." + table.getPaimonTable(), "table",
                table.getLayer().name(), null, Map.of("tableId", table.getId(),
                        "recordCount", defaultValue(table.getRecordCount(), 0L),
                        "businessDesc", defaultValue(table.getBusinessDesc(), ""))));
    }

    private void addEdge(Map<String, LineageGraphDTO.Edge> edges, String source, String target,
                         String type, String label, Long taskId) {
        String id = source + "->" + target + ":" + type;
        edges.putIfAbsent(id, new LineageGraphDTO.Edge(id, source, target, type,
                blank(label) ? type : label, taskId));
    }

    private List<TableMapping> parseMappings(String json) {
        if (blank(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("任务表映射 JSON 无法解析", exception);
        }
    }

    private TableRef tableRef(String raw) {
        String[] parts = raw.replace("`", "").split("\\.");
        return parts.length == 1 ? new TableRef("ods", parts[0])
                : new TableRef(parts[parts.length - 2], parts[parts.length - 1]);
    }

    private String dwhNodeId(String database, String table) { return "table:" + database + "." + table; }
    private String tableKey(String database, String table) { return (database + "." + table).toLowerCase(Locale.ROOT); }
    private String inferLayer(String database) {
        String value = database.toLowerCase(Locale.ROOT);
        return List.of("ods", "dwd", "dws", "ads").contains(value) ? value : "other";
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private <T> T defaultValue(T value, T fallback) { return value == null ? fallback : value; }

    private record TableMapping(String sourceTable, String targetDb, String targetTable, String syncMode) {}
    private record TableRef(String database, String table) {}
}
