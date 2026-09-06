package com.rtdwh.repository;
import com.rtdwh.entity.TaskRunAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface TaskRunAttemptRepository extends JpaRepository<TaskRunAttempt, Long> {
    List<TaskRunAttempt> findByInstanceIdOrderByAttemptNoDesc(Long instanceId);
}
