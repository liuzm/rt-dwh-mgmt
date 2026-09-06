package com.rtdwh.service;

import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.WorkflowDTO;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DatasetProductionService {
    @org.springframework.beans.factory.annotation.Value("${doris.catalog:rtdwh_paimon}")
    private String platformCatalog = "rtdwh_paimon";
    private final TaskOutputDatasetRepository outputRepository;
    private final DatasetProductionRepository productionRepository;
    private final DwhTableMetaRepository tableMetaRepository;
    private final QualityCheckService qualityCheckService;
    private final QueryAccessScopeService accessScopeService;
    private final TaskReleaseContractService releaseContracts;
    private final DatasetProductionCheckRepository checks;

    public List<TaskOutputDataset> outputs(Long taskId) {
        return outputRepository.findByTaskIdAndEnabledTrueOrderById(taskId);
    }

    public List<TaskOutputDataset> outputs(Long taskId, Long userId) {
        return accessScopeService.filterAllowed(userId, outputs(taskId),
                TaskOutputDataset::getCatalogName, TaskOutputDataset::getDatabaseName, TaskOutputDataset::getTableName);
    }

    @Transactional
    public List<TaskOutputDataset> replaceOutputs(Long taskId, List<WorkflowDTO.OutputDatasetRequest> requests) {
        List<TaskOutputDataset> existing = new ArrayList<>(outputRepository.findByTaskIdOrderById(taskId));
        existing.forEach(output -> output.setEnabled(false));
        Map<String, TaskOutputDataset> byName = new LinkedHashMap<>();
        existing.forEach(output -> byName.put(key(output.getCatalogName(), output.getDatabaseName(), output.getTableName()), output));

        List<TaskOutputDataset> enabled = new ArrayList<>();
        if (requests != null) {
            for (WorkflowDTO.OutputDatasetRequest request : requests) {
                String catalog = identifier(request.getCatalogName(), "Catalog");
                String database = identifier(request.getDatabaseName(), "Database");
                String table = identifier(request.getTableName(), "Table");
                String key = key(catalog, database, table);
                if (enabled.stream().anyMatch(output -> key(output.getCatalogName(), output.getDatabaseName(), output.getTableName()).equals(key))) {
                    throw new IllegalArgumentException("产出数据集重复: " + catalog + "." + database + "." + table);
                }
                TaskOutputDataset output = byName.getOrDefault(key, TaskOutputDataset.builder()
                        .taskId(taskId).catalogName(catalog).databaseName(database).tableName(table).build());
                output.setLayer(parseLayer(request.getLayer()));
                if (platformCatalog.equals(catalog)) {
                    DwhTableMeta asset = tableMetaRepository.findByPaimonDbAndPaimonTable(database, table)
                            .orElseGet(() -> DwhTableMeta.builder().paimonDb(database).paimonTable(table)
                                    .catalogName(platformCatalog).layer(output.getLayer()).build());
                    output.setAssetId(tableMetaRepository.saveAndFlush(asset).getAssetId());
                }
                output.setOwner(trim(request.getOwner()));
                output.setBusinessDesc(trim(request.getBusinessDesc()));
                output.setSlaMinutes(Math.max(1, Math.min(request.getSlaMinutes() == null ? 1440 : request.getSlaMinutes(), 525600)));
                output.setQualityGateEnabled(Boolean.TRUE.equals(request.getQualityGateEnabled()));
                output.setEnabled(true);
                if (output.getId() == null) existing.add(output);
                enabled.add(output);
            }
        }
        outputRepository.saveAll(existing);
        return enabled;
    }

    @Transactional
    public List<TaskOutputDataset> replaceOutputs(Long taskId, List<WorkflowDTO.OutputDatasetRequest> requests,
                                                   Long userId) {
        for (TaskOutputDataset existing : outputRepository.findByTaskIdOrderById(taskId)) {
            assertAllowed(userId, existing.getCatalogName(), existing.getDatabaseName(), existing.getTableName());
        }
        if (requests != null) {
            for (WorkflowDTO.OutputDatasetRequest request : requests) {
                assertAllowed(userId, request.getCatalogName(), request.getDatabaseName(), request.getTableName());
            }
        }
        return replaceOutputs(taskId, requests);
    }

    public List<DatasetProduction> productions(Long outputId, int limit) {
        outputRepository.findById(outputId).orElseThrow(() -> new IllegalArgumentException("产出数据集不存在: " + outputId));
        return productionRepository.findByOutputDatasetIdOrderByProducedAtDesc(
                outputId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    public List<DatasetProduction> productions(Long outputId, int limit, Long userId) {
        TaskOutputDataset output = outputRepository.findById(outputId)
                .orElseThrow(() -> new IllegalArgumentException("产出数据集不存在: " + outputId));
        if (!accessScopeService.allowed(userId, output.getCatalogName(), output.getDatabaseName(), output.getTableName())) {
            throw new IllegalArgumentException("无权查看该产出数据集");
        }
        return productionRepository.findByOutputDatasetIdOrderByProducedAtDesc(
                outputId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public void recordSuccess(TaskRunInstance instance) { recordSuccess(instance, false); }

    public void recordSuccess(TaskRunInstance instance, boolean recheck) {
        LocalDateTime now = LocalDateTime.now();
        TaskReleaseContractService.Contract contract = releaseContracts.forInstance(instance);
        List<TaskReleaseContractService.Output> frozenOutputs = contract == null
                ? outputs(instance.getTaskId()).stream().map(value -> new TaskReleaseContractService.Output(value, null)).toList()
                : contract.outputs();
        for (TaskReleaseContractService.Output frozen : frozenOutputs) {
            TaskOutputDataset output = frozen.definition();
            String deliveryKey = instance.getId() + ":" + output.getId();
            DatasetProduction production = productionRepository.findByDeliveryKey(deliveryKey).orElse(null);
            if (production != null && !recheck) continue;
            if (production == null) production = productionRepository.saveAndFlush(DatasetProduction.builder()
                    .deliveryKey(deliveryKey).assetId(output.getAssetId()).outputDatasetId(output.getId()).taskId(instance.getTaskId()).instanceId(instance.getId())
                    .definitionVersionId(instance.getDefinitionVersionId()).attemptId(instance.getActiveAttemptId())
                    .businessDate(instance.getBusinessDate()).windowStart(instance.getWindowStart()).windowEnd(instance.getWindowEnd())
                    .status("checking").producedAt(now).build());
            boolean available = true;
            QualityCheckSummary summary = null;
            if (Boolean.TRUE.equals(output.getQualityGateEnabled())) {
                summary = contract == null ? qualityCheckService.runChecksForTableWithSummary(
                        output.getCatalogName(), output.getDatabaseName(), output.getTableName())
                        : qualityCheckService.runFrozenProductionRules(frozen.rules(), instance.getWindowStart(), instance.getWindowEnd());
                available = summary.total() > 0 && summary.abnormalCount() == 0;
            }
            String reason = summary == null ? "未启用质量门禁" : summary.total() == 0 ? "未配置门禁规则"
                    : summary.errorCount() > 0 ? "质量检测执行异常" : summary.failed() > 0 ? "质量规则未通过" : "质量规则通过";
            production.setStatus(available ? "available" : "blocked"); production.setReason(reason);
            production.setQualityBatchId(summary == null ? null : summary.batchId()); production.setCheckedAt(now);
            productionRepository.save(production);
            checks.save(DatasetProductionCheck.builder().productionId(production.getId()).qualityBatchId(production.getQualityBatchId())
                    .status(production.getStatus()).reason(reason).checkedAt(now).build());
            if (!available) continue;
            // Update operational metadata only; never merge a frozen definition over the current draft.
            LocalDateTime producedAt = production.getProducedAt();
            outputRepository.findById(output.getId()).ifPresent(current -> {
                // A quality recheck does not produce new data or renew freshness.
                if (producedAt != null && (current.getLastProducedAt() == null || !producedAt.isBefore(current.getLastProducedAt()))) {
                    current.setLastProducedAt(producedAt);
                    current.setLastInstanceId(instance.getId());
                    outputRepository.save(current);
                }
            });
            DwhTableMeta table = tableMetaRepository.findByPaimonDbAndPaimonTable(output.getDatabaseName(), output.getTableName())
                    .orElseGet(() -> DwhTableMeta.builder().paimonDb(output.getDatabaseName()).paimonTable(output.getTableName())
                            .sensitivityLevel("internal").lifecycleStatus("active").tags("[\"scheduled-output\"]").build());
            table.setLayer(output.getLayer());
            if (output.getOwner() != null) table.setOwner(output.getOwner());
            if (output.getBusinessDesc() != null) table.setBusinessDesc(output.getBusinessDesc());
            tableMetaRepository.save(table);
        }
    }

    /** Legacy dependencies require every declared output of this execution to be available. */
    public boolean isDeliveryAvailable(TaskRunInstance instance) {
        List<DatasetProduction> productions = productionRepository.findByInstanceId(instance.getId());
        if (productions.stream().anyMatch(item -> !"available".equals(item.getStatus()))) return false;
        TaskReleaseContractService.Contract contract = releaseContracts.forInstance(instance);
        List<TaskOutputDataset> expected = contract == null ? outputs(instance.getTaskId())
                : contract.outputs().stream().map(TaskReleaseContractService.Output::definition).toList();
        return expected.stream().allMatch(output -> productions.stream()
                .anyMatch(item -> output.getId().equals(item.getOutputDatasetId())
                        && "available".equals(item.getStatus())));
    }

    public List<DatasetProductionCheck> checks(Long productionId, Long actor) {
        DatasetProduction production = productionRepository.findById(productionId).orElseThrow();
        // Reuse the output-level access guard before returning any quality evidence.
        productions(production.getOutputDatasetId(), 1, actor);
        return checks.findByProductionIdOrderByIdDesc(productionId);
    }

    private DwhTableMeta.TableLayer parseLayer(String value) {
        try { return DwhTableMeta.TableLayer.valueOf(value.toLowerCase(Locale.ROOT)); }
        catch (Exception exception) { throw new IllegalArgumentException("数据分层仅支持 ods、dwd、dws、ads"); }
    }

    private String identifier(String value, String label) {
        String result = value == null ? "" : value.trim();
        if (!result.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException(label + " 名称格式不正确");
        return result;
    }

    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String key(String catalog, String database, String table) {
        return (catalog + "." + database + "." + table).toLowerCase(Locale.ROOT);
    }

    private void assertAllowed(Long userId, String catalog, String database, String table) {
        if (!accessScopeService.allowed(userId, catalog, database, table)) {
            throw new IllegalArgumentException("无权登记产出数据集: " + catalog + "." + database + "." + table);
        }
    }
}
