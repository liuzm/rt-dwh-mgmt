package com.rtdwh.repository;

import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Operation;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TableMaintenanceLogRepository extends JpaRepository<TableMaintenanceLog, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TableMaintenanceLog m where m.id = :id")
    java.util.Optional<TableMaintenanceLog> findByIdForUpdate(@Param("id") Long id);

    List<TableMaintenanceLog> findByTableMetaIdOrderByStartedAtDesc(Long tableMetaId);

    List<TableMaintenanceLog> findByOperationOrderByStartedAtDesc(Operation operation);

    List<TableMaintenanceLog> findByStatus(Status status);

    boolean existsByTableMetaIdAndStatusIn(Long tableMetaId, List<Status> statuses);

    @Query("select m.id from TableMaintenanceLog m where m.contractOrigin = 'bound_v1' and (m.status in :statuses or m.cleanupStatus = 'pending') order by m.id")
    List<Long> findRecoverableIds(@Param("statuses") List<Status> statuses);

    @Query("SELECT m FROM TableMaintenanceLog m WHERE " +
           "(:operation IS NULL OR m.operation = :operation) AND " +
           "(:status IS NULL OR m.status = :status) AND " +
           "(:tableMetaId IS NULL OR m.tableMetaId = :tableMetaId) " +
           "ORDER BY m.startedAt DESC")
    List<TableMaintenanceLog> searchLogs(@Param("operation") Operation operation,
                                          @Param("status") Status status,
                                          @Param("tableMetaId") Long tableMetaId);
}
