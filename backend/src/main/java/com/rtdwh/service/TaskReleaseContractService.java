package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Immutable contract stored alongside the existing task snapshot, preserving old snapshot readers. */
@Service
@RequiredArgsConstructor
public class TaskReleaseContractService {
    private final TaskDependencyRepository dependencies;
    private final TaskOutputDatasetRepository outputs;
    private final TaskDefinitionVersionRepository versions;
    private final QualityCheckService quality;
    private final ObjectMapper mapper;
    private final QueryAccessScopeService access;
    private final TaskParameterService parameters;
    private final RuntimeEnvironmentService runtime;
    private final TaskAccessAuditService audit;
    @org.springframework.beans.factory.annotation.Value("${doris.catalog:rtdwh_paimon}")
    private String platformCatalog = "rtdwh_paimon";

    public void preparePublication(SyncTask definition) {
        TaskCapabilityPolicy.requireSupported(definition.getTaskType(), definition.getScenarioCode());
        if (definition.getTaskType() != SyncTask.TaskType.cdc_sync) {
            definition.setParameterSchemaJson(parameters.validateTemplate(definition.getFlinkSql(), definition.getParameterSchemaJson()));
        }
        runtime.freeze(definition);
    }

    public void validateRuntime(SyncTask definition) { runtime.validate(definition); }

    public String parametersForVersion(TaskDefinitionVersion version, String supplied) {
        forVersion(version);
        try {
            SyncTask definition = mapper.readValue(version.getSnapshotJson(), SyncTask.class);
            return parameters.normalize(definition.getParameterSchemaJson(), supplied);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("发布参数契约无法解析", e); }
    }

    public void validateExecution(TaskRunInstance instance, SyncTask definition) {
        boolean allowed = false;
        try {
            if (instance.getCreatedBy() == null) throw new IllegalArgumentException("运行实例缺少执行身份");
            runtime.validate(definition);
            String rendered = parameters.render(definition.getFlinkSql(), definition.getParameterSchemaJson(),
                    instance.getParametersJson(), instance.getBusinessDate());
            access.validate(instance.getCreatedBy(), rendered, platformCatalog, "ods");
            Contract contract = forInstance(instance);
            if (contract != null) for (Output output : contract.outputs()) {
                TaskOutputDataset value = output.definition();
                if (!access.allowed(instance.getCreatedBy(), value.getCatalogName(), value.getDatabaseName(), value.getTableName()))
                    throw new IllegalArgumentException("产出数据权限已撤销，禁止提交");
            }
            allowed = true;
        } finally {
            audit.record(instance.getTaskId(), instance.getDefinitionVersionId(), instance.getId(), instance.getCreatedBy(), "execute", allowed);
        }
    }

    public boolean canReadVersion(TaskDefinitionVersion version, Long userId) {
        try {
            Contract contract = forVersion(version);
            SyncTask task = mapper.readValue(version.getSnapshotJson(), SyncTask.class);
            if (!access.canAccessSql(userId, parameters.forAccessCheck(task.getFlinkSql()), platformCatalog, "ods")) return false;
            return contract == null || contract.outputs().stream().allMatch(output -> access.allowed(userId,
                    output.definition().getCatalogName(), output.definition().getDatabaseName(), output.definition().getTableName()));
        } catch (Exception invalidOrDenied) { return false; }
    }

    public String snapshot(Long taskId) {
        try {
            List<Output> frozenOutputs = outputs.findByTaskIdAndEnabledTrueOrderById(taskId).stream()
                    .map(value -> new Output(value, Boolean.TRUE.equals(value.getQualityGateEnabled())
                            ? quality.snapshotRulesForTable(value.getCatalogName(), value.getDatabaseName(), value.getTableName())
                            : List.of())).toList();
            return mapper.writeValueAsString(new Contract(1, dependencies.findByDownstreamTaskId(taskId), frozenOutputs));
        } catch (Exception error) { throw new IllegalStateException("发布契约生成失败", error); }
    }

    public Contract forVersion(TaskDefinitionVersion version) {
        if (version.getContractJson() == null) return null; // explicitly legacy, never invent past rules or dependencies
        try {
            if (version.getContractHash() == null || !version.getContractHash().equals(fingerprint(version.getSnapshotJson(), version.getContractJson()))) {
                throw new IllegalStateException("发布契约指纹不匹配");
            }
            Contract contract = mapper.readValue(version.getContractJson(), Contract.class);
            if (contract.format() != 1 || contract.dependencies() == null || contract.outputs() == null) {
                throw new IllegalStateException("不支持的发布契约格式");
            }
            return contract;
        } catch (Exception error) { throw new IllegalStateException("发布契约损坏，禁止使用草稿替代", error); }
    }

    public Contract forInstance(TaskRunInstance instance) {
        if (instance.getDefinitionVersionId() == null) return null;
        TaskDefinitionVersion version = versions.findById(instance.getDefinitionVersionId())
                .filter(value -> value.getTaskId().equals(instance.getTaskId()))
                .orElseThrow(() -> new IllegalStateException("运行实例绑定的版本不存在"));
        return forVersion(version);
    }

    public List<TaskDependency> dependencies(TaskDefinitionVersion version) {
        Contract contract = forVersion(version);
        return contract == null ? dependencies.findByDownstreamTaskId(version.getTaskId()) : contract.dependencies();
    }

    public List<TaskDependency> dependencies(TaskRunInstance instance) {
        Contract contract = forInstance(instance);
        return contract == null ? dependencies.findByDownstreamTaskId(instance.getTaskId()) : contract.dependencies();
    }

    public static String fingerprint(String taskJson, String contractJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((taskJson + "\n" + contractJson).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("无法计算发布指纹", error); }
    }

    public record Contract(int format, List<TaskDependency> dependencies, List<Output> outputs) {}
    public record Output(TaskOutputDataset definition, List<QualityRule> rules) {}
}
