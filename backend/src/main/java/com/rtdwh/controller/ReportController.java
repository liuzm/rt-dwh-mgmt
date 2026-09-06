package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.service.QueryService;
import com.rtdwh.service.ReportService;
import com.rtdwh.service.ReportScheduleService;
import com.rtdwh.service.ReportParameterRenderer;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('report:view')")
public class ReportController {

    private final ReportService reportService;
    private final QueryService queryService;
    private final ReportScheduleService reportScheduleService;
    private final SecurityContextUtil securityContextUtil;
    private final ReportParameterRenderer parameterRenderer;

    @GetMapping
    public ApiResponse<List<ReportTemplate>> listReports() {
        return ApiResponse.success(reportService.listReports(securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReportTemplate> getReport(@PathVariable Long id) {
        return ApiResponse.success(reportService.getReport(id, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('report:create')")
    public ApiResponse<ReportTemplate> createReport(@RequestBody ReportTemplate template) {
        Long userId = securityContextUtil.getCurrentUserId();
        return ApiResponse.success("Report created", reportService.createReport(template, userId));
    }

    @RequestMapping(value = "/{id}/data", method = {RequestMethod.GET, RequestMethod.POST})
    public ApiResponse<Map<String, Object>> getReportData(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> parameters) {
        ReportTemplate report = reportService.getReport(id, securityContextUtil.getCurrentUserId());
        if (!Boolean.TRUE.equals(report.getIsPublished())) {
            throw new IllegalStateException("报告尚未发布，无法查询");
        }

        String sql = parameterRenderer.render(report.getSqlQuery(), report.getFilterConfig(), parameters);
        Map<String, Object> result = queryService.executeReportQuery(sql, securityContextUtil.getCurrentUserId());
        return ApiResponse.success(result);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('report:create')")
    public ApiResponse<ReportTemplate> updateReport(@PathVariable Long id, @RequestBody ReportTemplate template) {
        return ApiResponse.success("Report updated", reportService.updateReport(
                id, template, securityContextUtil.getCurrentUserId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('report:create')")
    public ApiResponse<Void> deleteReport(@PathVariable Long id) {
        reportService.deleteReport(id, securityContextUtil.getCurrentUserId());
        return ApiResponse.success("Report deleted", null);
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAuthority('report:create')")
    public ApiResponse<com.rtdwh.entity.ReportRun> runNow(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> parameters) {
        return ApiResponse.success("报表执行完成", reportScheduleService.runNow(
                id, securityContextUtil.getCurrentUserId(), parameters));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<List<com.rtdwh.entity.ReportRun>> runs(
            @PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(reportScheduleService.listRuns(
                id, limit, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/{id}/runs/{runId}")
    public ApiResponse<Map<String, Object>> runResult(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.success(reportScheduleService.result(
                id, runId, securityContextUtil.getCurrentUserId()));
    }
}
