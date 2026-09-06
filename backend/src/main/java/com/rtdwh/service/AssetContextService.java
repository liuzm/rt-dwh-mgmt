package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class AssetContextService {
    private final DwhTableMetaRepository tables;
    private final TaskDefinitionVersionRepository versions;
    private final TaskOutputDatasetRepository outputs;
    private final DatasetProductionRepository productions;
    private final SyncTaskService tasks;
    private final TaskReleaseContractService contracts;
    private final SqlAssetReferenceService references;
    private final ReportService reports;
    private final DataServiceService services;
    private final ReportParameterRenderer reportParameters;
    private final DorisConnectionService doris;
    private final QueryAccessScopeService access;
    private final SecurityContextUtil security;
    private final ObjectMapper mapper;
    private final ManagedViewRepository managedViews;
    private final ManagedViewVersionRepository viewVersions;
    private final ViewDependencyService viewDependencies;
    @Value("${doris.catalog:rtdwh_paimon}") private String platformCatalog = "rtdwh_paimon";

    public record Usage(String kind, Long id, String name, String relation, Long versionId, Integer versionNo, String evidence, String href) {}
    public record RelatedAsset(String assetId, String name, String direction) {}
    public record Context(List<Usage> usages, List<DatasetProduction> productions, List<RelatedAsset> relatedAssets, String coverage) {}

    @Transactional(readOnly = true)
    public Context context(DwhTableMeta asset, Long actor) {
        String catalog = asset.getCatalogName() == null ? platformCatalog : asset.getCatalogName();
        List<Usage> usages = new ArrayList<>();
        Map<String, RelatedAsset> related = new LinkedHashMap<>();
        List<SyncTask> visibleTasks = security.hasAuthority("task:view") ? tasks.listTasksForUser(actor, null, null, null) : List.of();
        Set<Long> visibleIds = new HashSet<>(); visibleTasks.forEach(task -> visibleIds.add(task.getId()));
        for (SyncTask task : visibleTasks) {
            if (task.getPublishedVersionId() == null) continue;
            var version = versions.findById(task.getPublishedVersionId()).filter(v -> task.getId().equals(v.getTaskId())).orElse(null);
            if (version == null || !contracts.canReadVersion(version, actor)) continue;
            try {
                var contract = contracts.forVersion(version);
                var definition = mapper.readValue(version.getSnapshotJson(), SyncTask.class);
                boolean declared = contract != null && contract.outputs().stream().anyMatch(o -> matches(asset, catalog,
                        o.definition().getCatalogName(), o.definition().getDatabaseName(), o.definition().getTableName()));
                boolean mapped = false;
                if (definition.getTableMappings() != null) for (var mapping : mapper.readTree(definition.getTableMappings())) {
                    if (matches(asset, catalog, platformCatalog, mapping.path("targetDb").asText("ods"), mapping.path("targetTable").asText())) mapped = true;
                }
                var parsed = definition.getFlinkSql() == null ? new SqlAssetReferenceService.Result(false, List.of())
                        : references.inspect(new TaskParameterService(mapper).forAccessCheck(definition.getFlinkSql()), platformCatalog, "ods");
                boolean writes = parsed.references().stream().anyMatch(r -> r.output() && matches(asset, catalog, r.catalog(), r.database(), r.table()));
                boolean reads = parsed.references().stream().anyMatch(r -> r.input() && matches(asset, catalog, r.catalog(), r.database(), r.table()));
                if (declared || mapped || writes) usages.add(new Usage("task", task.getId(), task.getTaskName(), "producer", version.getId(), version.getVersionNo(),
                        declared ? "published_output" : mapped ? "published_mapping" : "published_sql_ast", "/sync-task/detail/" + task.getId()));
                if (reads) usages.add(new Usage("task", task.getId(), task.getTaskName(), "consumer", version.getId(), version.getVersionNo(), "published_sql_ast", "/sync-task/detail/" + task.getId()));
                if (declared || mapped || writes || reads) for (var ref : parsed.references()) {
                    if ((declared || mapped || writes) && ref.input()) addRelated(related, asset, ref.catalog(), ref.database(), ref.table(), "upstream", actor);
                    if (reads && ref.output()) addRelated(related, asset, ref.catalog(), ref.database(), ref.table(), "downstream", actor);
                }
                if (reads && contract != null) for (var output : contract.outputs()) {
                    var o = output.definition(); addRelated(related, asset, o.getCatalogName(), o.getDatabaseName(), o.getTableName(), "downstream", actor);
                }
            } catch (Exception unsupported) { /* No guessed relations from malformed or unsupported definitions. */ }
        }
        if (security.hasAuthority("report:view")) for (var report : reports.listReports(actor)) {
            try {
                var refs = references.inspect(reportParameters.sqlForAccessCheck(report.getSqlQuery(), report.getFilterConfig()), doris.getCatalog(), doris.getDatabase());
                if (refs.references().stream().anyMatch(r -> r.input() && matches(asset, catalog, r.catalog(), r.database(), r.table())))
                    usages.add(new Usage("report", report.getId(), report.getReportName(), "consumer", null, null, "current_report_sql_ast", "/query/report"));
            } catch (IllegalArgumentException unsupported) { /* Partial parsing is not evidence. */ }
        }
        if (security.hasAuthority("data-service:view")) for (var service : services.publishedDefinitions(actor)) {
            try {
                var refs = references.inspect(reportParameters.sqlForAccessCheck(service.getSqlTemplate(), service.getParameterConfig()), service.getCatalogName(), service.getDatabaseName());
                if (refs.references().stream().anyMatch(r -> r.input() && matches(asset, catalog, r.catalog(), r.database(), r.table())))
                    usages.add(new Usage("service", service.getId(), service.getServiceName(), "consumer", service.getPublishedVersionId(), service.getApiVersion(), "published_service_sql_ast", "/query/data-service"));
            } catch (IllegalArgumentException unsupported) { /* Partial parsing is not evidence. */ }
        }
        for (var view : managedViews.findAll()) {
            if (view.getPublishedVersionId() == null) continue;
            var viewAsset = tables.findById(view.getTableMetaId()).orElse(null);
            var version = viewVersions.findById(view.getPublishedVersionId()).filter(v -> view.getId().equals(v.getViewId())).orElse(null);
            if (viewAsset == null || version == null || !access.allowed(actor, viewAsset.getCatalogName(), viewAsset.getPaimonDb(), viewAsset.getPaimonTable())) continue;
            try {
                var deps = viewDependencies.read(version.getDependenciesJson());
                if (deps.stream().anyMatch(d -> !access.allowed(actor,d.name().catalog(),d.name().database(),d.name().table()))) continue;
                if (deps.stream().anyMatch(d -> matches(asset,catalog,d.name().catalog(),d.name().database(),d.name().table()))) {
                    usages.add(new Usage("view",view.getId(),viewAsset.getPaimonTable(),"consumer",version.getId(),version.getVersionNo(),"published_view_contract","/dwh/assets/"+viewAsset.getAssetId()));
                    addRelated(related,asset,viewAsset.getCatalogName(),viewAsset.getPaimonDb(),viewAsset.getPaimonTable(),"downstream",actor);
                }
                if (viewAsset.getId().equals(asset.getId())) for(var dep:deps)
                    addRelated(related,asset,dep.name().catalog(),dep.name().database(),dep.name().table(),"upstream",actor);
            } catch (IllegalArgumentException invalid) { /* Invalid dependency records are not evidence. */ }
        }
        var outputIds = outputs.findByCatalogNameAndDatabaseNameAndTableName(catalog, asset.getPaimonDb(), asset.getPaimonTable()).stream()
                .filter(o -> visibleIds.contains(o.getTaskId())).map(TaskOutputDataset::getId).toList();
        var delivered = outputIds.isEmpty() ? List.<DatasetProduction>of() : productions.findByOutputDatasetIdInOrderByProducedAtDesc(outputIds, PageRequest.of(0, 100)).stream()
                .filter(p -> p.getDefinitionVersionId() != null && versions.findById(p.getDefinitionVersionId())
                        .filter(v -> Objects.equals(v.getTaskId(), p.getTaskId()) && contracts.canReadVersion(v, actor)).isPresent()).toList();
        return new Context(usages, delivered, new ArrayList<>(related.values()),
                "任务关系取自可见的当前发布版本；报表与服务取当前定义。View 关系取已发布的直接依赖；SQL 仅覆盖可解析子集，不替代完整的传递影响评估。产出最多展示最近 100 条。");
    }

    private void addRelated(Map<String, RelatedAsset> result, DwhTableMeta current, String catalog, String database, String table, String direction, Long actor) {
        if (!access.allowed(actor, catalog, database, table)) return;
        var candidate = platformCatalog.equals(catalog) ? tables.findByPaimonDbAndPaimonTable(database,table)
                : tables.findByCatalogNameAndPaimonDbAndPaimonTable(catalog,database,table);
        candidate.filter(t -> !t.getId().equals(current.getId())).ifPresent(t ->
                result.put(direction + t.getAssetId(), new RelatedAsset(t.getAssetId(), database + "." + table, direction)));
    }
    private boolean matches(DwhTableMeta asset, String assetCatalog, String catalog, String database, String table) {
        return assetCatalog.equalsIgnoreCase(Objects.toString(catalog, "")) && asset.getPaimonDb().equalsIgnoreCase(Objects.toString(database, ""))
                && asset.getPaimonTable().equalsIgnoreCase(Objects.toString(table, ""));
    }
}
