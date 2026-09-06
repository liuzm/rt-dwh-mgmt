package com.rtdwh.repository;

import com.rtdwh.entity.TaskRunInstance;
import java.util.Optional;

public interface TaskRunInstanceLockRepository {
    Optional<TaskRunInstance> findByIdForUpdate(Long id);
}
