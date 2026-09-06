package com.rtdwh.repository;

import com.rtdwh.entity.TaskDefinitionVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskDefinitionVersionRepository extends JpaRepository<TaskDefinitionVersion, Long> {
    List<TaskDefinitionVersion> findByTaskIdOrderByVersionNoDesc(Long taskId);
    Optional<TaskDefinitionVersion> findByTaskIdAndVersionNo(Long taskId, Integer versionNo);
    Optional<TaskDefinitionVersion> findFirstByTaskIdOrderByVersionNoDesc(Long taskId);
}
