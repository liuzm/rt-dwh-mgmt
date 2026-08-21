package com.rtdwh.repository;

import com.rtdwh.entity.SystemHealthStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemHealthStatusRepository extends JpaRepository<SystemHealthStatus, String> {
}
