package com.rtdwh.service;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class WorkflowDependencyService {
    private final TaskRunDependencyBindingRepository bindings;
    private final TaskRunInstanceRepository instances;
    private final DatasetProductionRepository productions;
    private final SyncTaskRepository tasks;
    private final TaskDefinitionVersionRepository versions;
    private final TaskReleaseContractService contracts;
    private final DatasetProductionService delivery;

    public List<TaskRunDependencyBinding> bindings(Long instanceId) { return bindings.findByInstanceIdOrderById(instanceId); }

    public void initialize(TaskRunInstance run, Map<Long, TaskDefinitionVersion> batchVersions) {
        for (TaskDependency dependency : contracts.dependencies(run)) {
            TaskDefinitionVersion upstream = batchVersions.get(dependency.getUpstreamTaskId());
            if (upstream == null) {
                SyncTask task = tasks.findById(dependency.getUpstreamTaskId()).orElseThrow();
                upstream = versions.findById(task.getPublishedVersionId()).filter(v -> v.getTaskId().equals(task.getId())).orElseThrow();
            }
            if (!contracts.canReadVersion(upstream, run.getCreatedBy())) throw new org.springframework.security.access.AccessDeniedException("无权使用上游发布版本");
            if ("data_available".equals(dependency.getConditionType())) {
                var contract = contracts.forVersion(upstream);
                var outputs = contract == null ? delivery.outputs(upstream.getTaskId(), run.getCreatedBy())
                        : contract.outputs().stream().map(TaskReleaseContractService.Output::definition).toList();
                if (outputs.stream().noneMatch(output -> Objects.equals(output.getId(), dependency.getOutputDatasetId())))
                    throw new IllegalStateException("上游当前发布版本不再声明依赖产出，请更新依赖并重新发布下游");
            }
            TaskRunDependencyBinding binding = TaskRunDependencyBinding.builder().instanceId(run.getId()).dependencyId(dependency.getId())
                    .upstreamTaskId(dependency.getUpstreamTaskId()).upstreamVersionId(upstream.getId()).outputDatasetId(dependency.getOutputDatasetId())
                    .conditionType(dependency.getConditionType()).bindingPolicy(run.getBindingPolicy())
                    .windowStart(run.getWindowStart()).windowEnd(run.getWindowEnd()).build();
            if ("batch_only".equals(run.getBindingPolicy())) {
                TaskRunInstance upstreamRun = instances.findByTaskIdAndBatchIdAndBusinessDate(dependency.getUpstreamTaskId(), run.getBatchId(), run.getBusinessDate())
                        .orElseThrow(() -> new IllegalStateException("本批上游实例未创建"));
                if (!upstream.getId().equals(upstreamRun.getDefinitionVersionId())) throw new IllegalStateException("本批上游版本不一致");
                binding.setUpstreamInstanceId(upstreamRun.getId());
            }
            bindings.save(binding);
        }
    }

    public void assertAccess(TaskRunInstance run) {
        for (TaskRunDependencyBinding binding : bindings(run.getId())) {
            var version = versions.findById(binding.getUpstreamVersionId()).orElseThrow();
            if (!contracts.canReadVersion(version, run.getCreatedBy()))
                throw new org.springframework.security.access.AccessDeniedException("上游发布版本的访问权限已变更");
        }
    }

    public boolean ready(TaskRunInstance run) {
        List<TaskRunDependencyBinding> expected = bindings(run.getId());
        // Explicit compatibility for waiting instances created before binding support.
        if (run.getBindingPolicy() == null) return contracts.dependencies(run).stream().allMatch(dependency -> instances
                .findFirstByTaskIdAndBusinessDateOrderByCreatedAtDesc(dependency.getUpstreamTaskId(), run.getBusinessDate())
                .filter(up -> up.getStatus() == TaskRunInstance.RunStatus.success).filter(delivery::isDeliveryAvailable).isPresent());
        if (expected.size() != contracts.dependencies(run).size()) return false;
        return expected.stream().allMatch(this::satisfy);
    }

    private boolean satisfy(TaskRunDependencyBinding binding) {
        TaskRunInstance upstream = binding.getUpstreamInstanceId() == null ? null : instances.findById(binding.getUpstreamInstanceId()).orElse(null);
        if (upstream == null && "reuse_available".equals(binding.getBindingPolicy())) {
            if ("data_available".equals(binding.getConditionType())) {
                DatasetProduction production = productions.findFirstByOutputDatasetIdAndDefinitionVersionIdAndWindowStartAndWindowEndAndStatusOrderByIdDesc(
                        binding.getOutputDatasetId(), binding.getUpstreamVersionId(), binding.getWindowStart(), binding.getWindowEnd(), "available").orElse(null);
                if (production == null) return false;
                upstream = instances.findById(production.getInstanceId()).orElse(null);
            } else {
                upstream = instances.findFirstByTaskIdAndDefinitionVersionIdAndBusinessDateAndStatusOrderByIdDesc(
                        binding.getUpstreamTaskId(), binding.getUpstreamVersionId(), binding.getWindowStart(), TaskRunInstance.RunStatus.success).orElse(null);
            }
        }
        if (upstream == null || upstream.getStatus() != TaskRunInstance.RunStatus.success
                || !binding.getUpstreamTaskId().equals(upstream.getTaskId())
                || !binding.getUpstreamVersionId().equals(upstream.getDefinitionVersionId())
                || !binding.getWindowStart().equals(upstream.getWindowStart()) || !binding.getWindowEnd().equals(upstream.getWindowEnd())) return false;
        if ("data_available".equals(binding.getConditionType())) {
            if ("checking".equals(upstream.getDeliveryStatus())) return false;
            DatasetProduction production = binding.getProductionId() == null
                    ? productions.findByInstanceId(upstream.getId()).stream().filter(p -> matches(binding, p)).findFirst().orElse(null)
                    : productions.findById(binding.getProductionId()).filter(p -> matches(binding, p)).orElse(null);
            if (production == null || !upstream.getId().equals(production.getInstanceId())) return false;
            binding.setProductionId(production.getId());
        } else if (!"execution_success".equals(binding.getConditionType())) {
            if (!delivery.isDeliveryAvailable(upstream)) return false;
        }
        if (binding.getBoundAt() == null) {
            binding.setUpstreamInstanceId(upstream.getId()); binding.setBoundAt(LocalDateTime.now()); bindings.save(binding);
        }
        return true;
    }

    private boolean matches(TaskRunDependencyBinding binding, DatasetProduction production) {
        return "available".equals(production.getStatus()) && binding.getOutputDatasetId().equals(production.getOutputDatasetId())
                && binding.getUpstreamVersionId().equals(production.getDefinitionVersionId())
                && binding.getWindowStart().equals(production.getWindowStart()) && binding.getWindowEnd().equals(production.getWindowEnd());
    }
}
