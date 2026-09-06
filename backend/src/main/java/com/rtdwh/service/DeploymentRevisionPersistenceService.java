package com.rtdwh.service;

import com.rtdwh.entity.*;
import com.rtdwh.repository.TaskDeploymentRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;

@Service @RequiredArgsConstructor
public class DeploymentRevisionPersistenceService {
    private final TaskDeploymentRevisionRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskDeploymentRevision begin(SyncTask task, TaskDefinitionVersion version, Long actor, String action, String restorePath) {
        return repository.saveAndFlush(TaskDeploymentRevision.builder().taskId(task.getId())
                .definitionVersionId(version.getId()).contractHash(version.getContractHash()).requestedBy(actor)
                .actionType(action).status("deploying").restorePath(restorePath)
                .desiredParallelism(task.getParallelism()).createdAt(LocalDateTime.now()).build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void submitted(Long id, String jobId) {
        TaskDeploymentRevision revision = repository.findById(id).orElseThrow();
        revision.setFlinkJobId(jobId); revision.setStatus("submitted");
        revision.setObservedAt(LocalDateTime.now()); repository.save(revision);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void uncertain(Long id) {
        TaskDeploymentRevision revision = repository.findById(id).orElseThrow();
        revision.setStatus("unknown");
        revision.setErrorMessage("部署结果无法确认；请核对 Flink 作业，禁止盲目重复提交");
        repository.save(revision);
    }
}
