package com.rtdwh.repository;
import com.rtdwh.entity.TaskScheduleRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface TaskScheduleRevisionRepository extends JpaRepository<TaskScheduleRevision, Long> {
    Optional<TaskScheduleRevision> findFirstByTaskIdOrderByRevisionNoDesc(Long taskId);
    List<TaskScheduleRevision> findTop100ByTaskIdOrderByRevisionNoDesc(Long taskId);
}
