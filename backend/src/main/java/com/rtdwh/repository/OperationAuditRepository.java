package com.rtdwh.repository;

import com.rtdwh.entity.OperationAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;

public interface OperationAuditRepository extends JpaRepository<OperationAudit, Long>, JpaSpecificationExecutor<OperationAudit> {
    Page<OperationAudit> findByUsernameContainingIgnoreCase(String username, Pageable pageable);
    long countByCreatedAtAfter(LocalDateTime createdAt);
    long countByCreatedAtAfterAndSuccessFalse(LocalDateTime createdAt);
    long countByUsernameAndCreatedAtAfter(String username, LocalDateTime createdAt);
    long countByUsernameAndCreatedAtAfterAndSuccessFalse(String username, LocalDateTime createdAt);
}
