package com.rtdwh.repository;

import com.rtdwh.entity.MaintenanceRecoveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MaintenanceRecoveryEventRepository extends JpaRepository<MaintenanceRecoveryEvent, Long> {
    List<MaintenanceRecoveryEvent> findTop200ByMaintenanceIdOrderByIdDesc(Long maintenanceId);
}
