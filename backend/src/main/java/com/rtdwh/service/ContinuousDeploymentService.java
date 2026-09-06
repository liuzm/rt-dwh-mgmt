package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.*;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ContinuousDeploymentService {
    private final TaskDefinitionVersionRepository versions;
    private final TaskDeploymentRevisionRepository deployments;
    private final SyncTaskRepository tasks;
    private final TaskReleaseContractService contracts;
    private final CdcSqlGenerator generator;
    private final DatasourceService datasources;
    private final QueryAccessScopeService access;
    private final FlinkClusterService flink;
    private final ObjectMapper mapper;
    private final TaskAccessAuditService audit;
    @Value("${doris.catalog:rtdwh_paimon}") private String catalog = "rtdwh_paimon";

    @Transactional
    public TaskDefinitionVersion publish(SyncTask task, Long userId, String summary) {
        if (task.getExecutionMode() != SyncTask.ExecutionMode.continuous) throw new IllegalArgumentException("仅持续任务使用部署发布");
        assertScope(task, userId);
        try {
            SyncTask snapshot = mapper.readValue(mapper.writeValueAsString(task), SyncTask.class);
            if (task.getTaskType() == SyncTask.TaskType.cdc_sync) {
                snapshot.setFlinkSql(generator.generateReleaseSql(task, datasources.getDatasource(task.getSourceConfigId())));
            }
            snapshot.setFlinkJobId(null); snapshot.setCheckpointInfo(null); snapshot.setLastErrorMsg(null);
            snapshot.setSavepointTriggerId(null); snapshot.setActiveDeploymentId(null);
            contracts.preparePublication(snapshot);
            String taskJson = mapper.writeValueAsString(snapshot);
            String contractJson = contracts.snapshot(task.getId());
            TaskDefinitionVersion version = versions.saveAndFlush(TaskDefinitionVersion.builder().taskId(task.getId())
                    .versionNo(versions.findFirstByTaskIdOrderByVersionNoDesc(task.getId()).map(v -> v.getVersionNo() + 1).orElse(1))
                    .snapshotJson(taskJson).contractJson(contractJson)
                    .contractHash(TaskReleaseContractService.fingerprint(taskJson, contractJson))
                    .createdBy(userId).createdAt(LocalDateTime.now()).changeSummary(summary).build());
            task.setPublishedVersionId(version.getId()); task.setDefinitionStatus(SyncTask.DefinitionStatus.published);
            tasks.save(task);
            return version;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("持续任务发布失败", e); }
    }

    public Prepared prepare(SyncTask task, Long actor, boolean resume) {
        if (deployments.existsByTaskIdAndStatusInAndFlinkJobIdIsNull(task.getId(), List.of("deploying", "unknown"))) {
            throw new IllegalStateException("存在结果未确认的部署，请核对 Flink 作业后再操作");
        }
        Long versionId = task.getPublishedVersionId();
        if (resume && task.getActiveDeploymentId() != null) {
            versionId = deployments.findById(task.getActiveDeploymentId()).filter(d -> task.getId().equals(d.getTaskId())).orElseThrow().getDefinitionVersionId();
        }
        // Compatibility: the first start also publishes a version. Further starts use the published pointer.
        if (versionId == null) versionId = publish(task, actor, "首次启动时发布").getId();
        TaskDefinitionVersion version = versions.findById(versionId).filter(v -> v.getTaskId().equals(task.getId())).orElseThrow();
        boolean allowed = false;
        try {
            assertContractScope(version, actor);
            SyncTask executable = mapper.readValue(version.getSnapshotJson(), SyncTask.class);
            executable.setId(task.getId()); assertScope(executable, actor);
            contracts.validateRuntime(executable);
            if (executable.getTaskType() != SyncTask.TaskType.cdc_sync) {
                executable.setFlinkSql(new TaskParameterService(mapper).render(executable.getFlinkSql(), executable.getParameterSchemaJson(), "{}", null));
            }
            if (executable.getTaskType() == SyncTask.TaskType.cdc_sync) {
                executable.setFlinkSql(generator.bindReleaseCredentials(executable.getFlinkSql(),
                        datasources.getDatasource(executable.getSourceConfigId())));
            }
            allowed = true;
            return new Prepared(executable, version);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("发布定义无法解析", e); }
        finally { audit.record(task.getId(), version.getId(), null, actor, resume ? "resume" : "start", allowed); }
    }

    public List<TaskDefinitionVersion> versions(Long taskId, Long userId) {
        return versions.findByTaskIdOrderByVersionNoDesc(taskId).stream().filter(v -> {
            try { assertContractScope(v, userId); assertScope(mapper.readValue(v.getSnapshotJson(), SyncTask.class), userId); return true; }
            catch (Exception denied) { return false; }
        }).toList();
    }

    public List<TaskDeploymentRevision> deployments(Long taskId, Long userId) {
        Set<Long> allowed = new HashSet<>(); versions(taskId, userId).forEach(v -> allowed.add(v.getId()));
        return deployments.findTop100ByTaskIdOrderByIdDesc(taskId).stream()
                .filter(d -> allowed.contains(d.getDefinitionVersionId())).toList();
    }

    private void assertContractScope(TaskDefinitionVersion version, Long actor) {
        TaskReleaseContractService.Contract contract = contracts.forVersion(version);
        if (contract == null || access.isAdmin(actor)) return;
        for (TaskReleaseContractService.Output output : contract.outputs()) {
            TaskOutputDataset value = output.definition();
            if (!access.allowed(actor, value.getCatalogName(), value.getDatabaseName(), value.getTableName())) {
                throw new IllegalArgumentException("发布产出数据权限已撤销");
            }
        }
    }

    private void assertScope(SyncTask task, Long actor) {
        if (access.isAdmin(actor)) return;
        if (task.getTaskType() != SyncTask.TaskType.cdc_sync) {
            access.validate(actor, task.getFlinkSql(), catalog, "ods"); return;
        }
        try {
            var mappings = mapper.readTree(task.getTableMappings());
            if (!mappings.isArray() || mappings.isEmpty()) throw new IllegalArgumentException("缺少表映射");
            for (var mapping : mappings) {
                if (!access.allowed(actor, catalog, mapping.path("targetDb").asText("ods"), mapping.path("targetTable").asText()))
                    throw new IllegalArgumentException("发布版本的数据权限已撤销");
            }
        } catch (Exception denied) { throw new IllegalArgumentException("无权使用该持续任务发布版本", denied); }
    }

    @Scheduled(fixedDelayString = "${deployment.reconcile-ms:10000}", initialDelay = 20000)
    public void reconcile() {
        for (TaskDeploymentRevision revision : deployments.findTop200ByStatusInOrderByIdAsc(List.of("submitted", "running", "unknown"))) {
            if (revision.getFlinkJobId() == null) continue;
            try {
                Map<String, Object> observed = flink.getJobStatus(revision.getFlinkJobId());
                String state = String.valueOf(observed.get("flinkState"));
                revision.setStatus(switch (state) {
                    case "RUNNING" -> "running";
                    case "FAILED" -> "failed";
                    case "FINISHED", "CANCELED" -> "stopped";
                    default -> "unknown";
                });
                revision.setObservedAt(LocalDateTime.now());
                revision.setErrorMessage("unknown".equals(revision.getStatus()) ? "引擎状态待确认" : null);
                deployments.save(revision);
            } catch (Exception unavailable) {
                revision.setStatus("unknown"); revision.setErrorMessage("无法连接执行引擎，等待协调"); deployments.save(revision);
            }
        }
    }
    public record Prepared(SyncTask executable, TaskDefinitionVersion version) {}
}
