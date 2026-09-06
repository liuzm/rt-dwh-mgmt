package com.rtdwh.repository;
import com.rtdwh.entity.TaskRunDependencyBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface TaskRunDependencyBindingRepository extends JpaRepository<TaskRunDependencyBinding, Long> {
    List<TaskRunDependencyBinding> findByInstanceIdOrderById(Long instanceId);
}
