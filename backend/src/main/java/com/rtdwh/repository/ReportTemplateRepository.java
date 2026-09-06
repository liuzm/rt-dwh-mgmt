package com.rtdwh.repository;

import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.entity.ReportTemplate.ReportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {

    List<ReportTemplate> findByCreatorId(Long creatorId);

    List<ReportTemplate> findByIsPublished(Boolean isPublished);

    List<ReportTemplate> findByReportType(ReportType reportType);
    long countByIsPublishedTrue();
    List<ReportTemplate> findByReportNameContainingIgnoreCase(String keyword, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from ReportTemplate report where report.isPublished = true "
            + "and report.scheduleEnabled = true and report.nextRunAt <= :now order by report.nextRunAt asc")
    List<ReportTemplate> findDueForUpdate(LocalDateTime now, Pageable pageable);
}
