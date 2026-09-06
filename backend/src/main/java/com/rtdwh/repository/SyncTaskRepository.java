package com.rtdwh.repository;

import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.SyncTask.TaskStatus;
import com.rtdwh.entity.SyncTask.TaskType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncTaskRepository extends JpaRepository<SyncTask, Long> {

    List<SyncTask> findByStatus(TaskStatus status);

    List<SyncTask> findByStatusIn(List<TaskStatus> statuses);

    List<SyncTask> findByCreatorId(Long creatorId);

    List<SyncTask> findByTaskType(TaskType taskType);

    /** Serialize control-plane actions that make external Flink REST calls. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SyncTask t WHERE t.id = :id")
    Optional<SyncTask> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT t FROM SyncTask t WHERE " +
           "(:status IS NULL OR t.status = :status) AND " +
           "(:taskType IS NULL OR t.taskType = :taskType) AND " +
           "(:keyword IS NULL OR LOWER(t.taskName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<SyncTask> searchTasks(@Param("status") TaskStatus status,
                               @Param("taskType") TaskType taskType,
                               @Param("keyword") String keyword);

    @Query("SELECT COUNT(t) FROM SyncTask t WHERE t.status = :status")
    long countByStatus(@Param("status") TaskStatus status);
}
