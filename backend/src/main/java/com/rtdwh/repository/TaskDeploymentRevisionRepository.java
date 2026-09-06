package com.rtdwh.repository;
import com.rtdwh.entity.TaskDeploymentRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface TaskDeploymentRevisionRepository extends JpaRepository<TaskDeploymentRevision, Long> {
    List<TaskDeploymentRevision> findTop100ByTaskIdOrderByIdDesc(Long taskId);
    List<TaskDeploymentRevision> findTop200ByStatusInOrderByIdAsc(List<String> statuses);
    boolean existsByTaskIdAndStatusInAndFlinkJobIdIsNull(Long taskId, List<String> statuses);
}
