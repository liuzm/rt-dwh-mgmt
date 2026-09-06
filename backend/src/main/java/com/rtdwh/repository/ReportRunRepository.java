package com.rtdwh.repository;

import com.rtdwh.entity.ReportRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRunRepository extends JpaRepository<ReportRun, Long> {
    List<ReportRun> findByReportIdOrderByStartedAtDesc(Long reportId, Pageable pageable);
    long deleteByReportId(Long reportId);
}
