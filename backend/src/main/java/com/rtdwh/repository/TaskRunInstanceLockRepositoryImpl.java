package com.rtdwh.repository;

import com.rtdwh.entity.TaskRunInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import java.util.Optional;

@RequiredArgsConstructor
public class TaskRunInstanceLockRepositoryImpl implements TaskRunInstanceLockRepository {
    private final EntityManager entityManager;

    @Override
    public Optional<TaskRunInstance> findByIdForUpdate(Long id) {
        TaskRunInstance run = entityManager.find(TaskRunInstance.class, id, LockModeType.PESSIMISTIC_WRITE);
        // Authorization/list queries can already have loaded this entity in the
        // request's persistence context. A row lock alone does not refresh it.
        if (run != null) entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        return Optional.ofNullable(run);
    }
}
