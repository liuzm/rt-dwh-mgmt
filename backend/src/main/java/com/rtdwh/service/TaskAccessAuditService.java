package com.rtdwh.service;
import com.rtdwh.entity.TaskAccessCheck;
import com.rtdwh.repository.TaskAccessCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
@Service @RequiredArgsConstructor
public class TaskAccessAuditService {
    private final TaskAccessCheckRepository repository;
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(Long task, Long version, Long instance, Long actor, String action, boolean allowed) {
        repository.saveAndFlush(TaskAccessCheck.builder().taskId(task).definitionVersionId(version).instanceId(instance)
                .actorId(actor).action(action).allowed(allowed).reason(allowed ? "当前数据范围校验通过" : "当前数据范围或发布契约校验未通过")
                .checkedAt(LocalDateTime.now()).build());
    }
    public List<TaskAccessCheck> list(Long task) { return repository.findTop100ByTaskIdOrderByIdDesc(task); }
}
