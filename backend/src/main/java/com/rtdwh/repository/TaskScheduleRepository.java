package com.rtdwh.repository;

import com.rtdwh.entity.TaskSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskScheduleRepository extends JpaRepository<TaskSchedule, Long> {
    Optional<TaskSchedule> findByTaskId(Long taskId);
    List<TaskSchedule> findByEnabledTrue();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select schedule from TaskSchedule schedule where schedule.enabled = true and schedule.nextRunAt <= :now order by schedule.nextRunAt")
    List<TaskSchedule> findDueForUpdate(@Param("now") Instant now, Pageable pageable);
}
