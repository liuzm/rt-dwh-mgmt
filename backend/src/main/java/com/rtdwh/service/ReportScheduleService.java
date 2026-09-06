package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.ReportRun;
import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.repository.ReportRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportScheduleService {
    private final ReportService reportService;
    private final QueryService queryService;
    private final ReportRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final AlertNotifyService alertNotifyService;
    private final ReportParameterRenderer parameterRenderer;

    public int runDueReports() {
        int executed = 0;
        for (int index = 0; index < 20; index++) {
            ReportService.ReportClaim claim = reportService.claimNextDue(LocalDateTime.now()).orElse(null);
            if (claim == null) break;
            execute(claim.report(), "scheduled", claim.scheduledAt(), claim.report().getCreatorId(), null);
            executed++;
        }
        return executed;
    }

    public ReportRun runNow(Long reportId, Long userId, Map<String, Object> parameters) {
        ReportTemplate report = reportService.getReport(reportId, userId);
        if (!Boolean.TRUE.equals(report.getIsPublished())) {
            throw new IllegalStateException("报表尚未发布，不能执行调度");
        }
        return execute(report, "manual", LocalDateTime.now(), userId, parameters);
    }

    public List<ReportRun> listRuns(Long reportId, int limit, Long userId) {
        reportService.getReport(reportId, userId);
        return runRepository.findByReportIdOrderByStartedAtDesc(
                reportId, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    public Map<String, Object> result(Long reportId, Long runId, Long userId) {
        reportService.getReport(reportId, userId);
        ReportRun run = runRepository.findById(runId)
                .filter(item -> item.getReportId().equals(reportId))
                .orElseThrow(() -> new IllegalArgumentException("报表运行记录不存在: " + runId));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run", run);
        if (run.getResultJson() != null) {
            try {
                response.put("result", objectMapper.readValue(run.getResultJson(), Map.class));
            } catch (Exception exception) {
                response.put("result", Map.of());
            }
        } else {
            response.put("result", Map.of());
        }
        return response;
    }

    private ReportRun execute(ReportTemplate report, String triggerType,
                              LocalDateTime scheduledAt, Long userId,
                              Map<String, Object> requestedParameters) {
        LocalDateTime started = LocalDateTime.now();
        ReportRun run = runRepository.save(ReportRun.builder()
                .reportId(report.getId())
                .triggerType(triggerType)
                .status("running")
                .scheduledAt(scheduledAt)
                .startedAt(started)
                .executedBy(userId)
                .deliveryStatus("skipped")
                .build());
        ReportScheduleConfig config = ReportScheduleConfig.parse(report.getScheduleConfig(), objectMapper);
        Map<String, Object> parameters = requestedParameters == null ? config.parameters() : requestedParameters;
        int attempt = 0;
        while (attempt <= config.maxRetries()) {
            attempt++;
            try {
                String renderedSql = parameterRenderer.render(
                        report.getSqlQuery(), report.getFilterConfig(), parameters);
                Map<String, Object> result = queryService.executeReportQuery(
                        renderedSql, userId, config.maxRows());
                String status = String.valueOf(result.getOrDefault("status", "failed"));
                run.setStatus("success".equalsIgnoreCase(status) ? "success" : "failed");
                run.setRowCount(number(result.get("rowCount")));
                run.setResultJson(objectMapper.writeValueAsString(result));
                run.setErrorMessage("success".equals(run.getStatus()) ? null
                        : String.valueOf(result.getOrDefault("errorMsg", "报表查询失败")));
            } catch (Exception exception) {
                run.setStatus("failed");
                run.setErrorMessage(concise(exception.getMessage()));
            }
            if ("success".equals(run.getStatus())) break;
        }
        run.setAttemptCount(attempt);
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(java.time.Duration.between(started, run.getFinishedAt()).toMillis());
        run = runRepository.save(run);
        deliver(report, run, config);
        run = runRepository.save(run);
        reportService.markRunFinished(report.getId(), run.getFinishedAt());
        cleanup(report);
        return run;
    }

    private void deliver(ReportTemplate report, ReportRun run, ReportScheduleConfig config) {
        if (!config.shouldNotify(run.getStatus())) {
            run.setDeliveryStatus("skipped");
            return;
        }
        try {
            AlertNotifyService.DeliveryResult delivery = alertNotifyService.sendReportResult(report, run, config);
            run.setDeliveryStatus(delivery.success() ? "success"
                    : delivery.delivered() > 0 ? "partial" : "failed");
            run.setDeliveryError(delivery.error().isBlank() ? null : delivery.error());
        } catch (Exception exception) {
            run.setDeliveryStatus("failed");
            run.setDeliveryError(concise(exception.getMessage()));
        }
    }

    private void cleanup(ReportTemplate report) {
        ReportScheduleConfig config = ReportScheduleConfig.parse(report.getScheduleConfig(), objectMapper);
        List<ReportRun> runs = runRepository.findByReportIdOrderByStartedAtDesc(
                report.getId(), PageRequest.of(0, 201));
        if (runs.size() > config.retainCount()) {
            runRepository.deleteAllInBatch(runs.subList(config.retainCount(), runs.size()));
        }
    }

    private Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String concise(String value) {
        if (value == null || value.isBlank()) return "报表执行失败";
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
