package com.rtdwh.repository;
import com.rtdwh.entity.TaskAccessCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface TaskAccessCheckRepository extends JpaRepository<TaskAccessCheck, Long> {
    List<TaskAccessCheck> findTop100ByTaskIdOrderByIdDesc(Long taskId);
}
