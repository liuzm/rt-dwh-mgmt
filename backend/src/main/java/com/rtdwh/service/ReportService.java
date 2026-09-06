package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.repository.ReportRunRepository;
import com.rtdwh.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportRunRepository reportRunRepository;
    private final ObjectMapper objectMapper;
    private final ReportParameterRenderer parameterRenderer;
    private final QueryAccessScopeService accessScopeService;
    private final DorisConnectionService dorisConnectionService;

    @Transactional(readOnly = true)
    public List<ReportTemplate> listReports() {
        return reportTemplateRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ReportTemplate> listReports(Long userId) {
        return accessScopeService.filterAllowedDorisSql(userId, reportTemplateRepository.findAll(),
                report -> parameterRenderer.sqlForAccessCheck(report.getSqlQuery(), report.getFilterConfig()), dorisConnectionService.getCatalog(), dorisConnectionService.getDatabase());
    }

    @Transactional(readOnly = true)
    public ReportTemplate getReport(Long id) {
        return reportTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表不存在: " + id));
    }

    @Transactional(readOnly = true)
    public ReportTemplate getReport(Long id, Long userId) {
        ReportTemplate report = getReport(id);
        assertAccess(report, userId);
        return report;
    }

    @Transactional
    public ReportTemplate createReport(ReportTemplate template, Long creatorId) {
        assertAccess(template, creatorId);
        template.setId(null);
        LocalDateTime now = LocalDateTime.now();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template.setIsPublished(false);
        template.setCreatorId(creatorId);
        applySchedule(template, now);
        return reportTemplateRepository.save(template);
    }

    @Transactional
    public ReportTemplate updateReport(Long id, ReportTemplate template, Long userId) {
        ReportTemplate existing = reportTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表不存在: " + id));
        assertAccess(existing, userId);
        assertAccess(template, userId);
        template.setId(existing.getId());
        template.setCreatedAt(existing.getCreatedAt());
        template.setUpdatedAt(LocalDateTime.now());
        template.setCreatorId(existing.getCreatorId());
        template.setLastRunAt(existing.getLastRunAt());
        applySchedule(template, LocalDateTime.now());
        return reportTemplateRepository.save(template);
    }

    @Transactional
    public void deleteReport(Long id, Long userId) {
        getReport(id, userId);
        reportRunRepository.deleteByReportId(id);
        reportTemplateRepository.deleteById(id);
    }

    @Transactional
    public Optional<ReportClaim> claimNextDue(LocalDateTime now) {
        Optional<ReportTemplate> candidate = reportTemplateRepository
                .findDueForUpdate(now, PageRequest.of(0, 1)).stream().findFirst();
        if (candidate.isEmpty()) return Optional.empty();
        ReportTemplate report = candidate.get();
        LocalDateTime scheduledAt = report.getNextRunAt();
        ReportScheduleConfig config = ReportScheduleConfig.parse(report.getScheduleConfig(), objectMapper);
        report.setNextRunAt(config.nextAfter(now));
        report.setUpdatedAt(now);
        reportTemplateRepository.save(report);
        return Optional.of(new ReportClaim(report, scheduledAt));
    }

    @Transactional
    public void markRunFinished(Long reportId, LocalDateTime finishedAt) {
        ReportTemplate report = reportTemplateRepository.findById(reportId).orElse(null);
        if (report == null) return;
        report.setLastRunAt(finishedAt);
        report.setUpdatedAt(finishedAt);
        reportTemplateRepository.save(report);
    }

    private void applySchedule(ReportTemplate template, LocalDateTime now) {
        parameterRenderer.validateTemplate(template.getSqlQuery(), template.getFilterConfig());
        ReportScheduleConfig config = ReportScheduleConfig.parse(template.getScheduleConfig(), objectMapper);
        template.setScheduleEnabled(config.enabled());
        template.setNextRunAt(config.enabled() ? config.nextAfter(now) : null);
    }

    private void assertAccess(ReportTemplate report, Long userId) {
        if (report.getSqlQuery() == null || !accessScopeService.canAccessDorisSql(userId, parameterRenderer.sqlForAccessCheck(report.getSqlQuery(), report.getFilterConfig()),
                dorisConnectionService.getCatalog(), dorisConnectionService.getDatabase())) {
            throw new IllegalArgumentException("无权访问报表涉及的数据表");
        }
    }

    public record ReportClaim(ReportTemplate report, LocalDateTime scheduledAt) {}
}
