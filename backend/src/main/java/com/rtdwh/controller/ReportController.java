package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.service.QueryService;
import com.rtdwh.service.ReportService;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final QueryService queryService;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping
    public ApiResponse<List<ReportTemplate>> listReports() {
        return ApiResponse.success(reportService.listReports());
    }

    @GetMapping("/{id}")
    public ApiResponse<ReportTemplate> getReport(@PathVariable Long id) {
        return ApiResponse.success(reportService.getReport(id));
    }

    @PostMapping
    public ApiResponse<ReportTemplate> createReport(@RequestBody ReportTemplate template) {
        Long userId = securityContextUtil.getCurrentUserId();
        return ApiResponse.success("Report created", reportService.createReport(template, userId));
    }

    @GetMapping("/{id}/data")
    public ApiResponse<Map<String, Object>> getReportData(@PathVariable Long id) {
        ReportTemplate report = reportService.getReport(id);
        if (!Boolean.TRUE.equals(report.getIsPublished())) {
            throw new IllegalStateException("报告尚未发布，无法查询");
        }

        Map<String, Object> result = queryService.executeReportQuery(report.getSqlQuery(), securityContextUtil.getCurrentUserId());
        return ApiResponse.success(result);
    }

    @PutMapping("/{id}")
    public ApiResponse<ReportTemplate> updateReport(@PathVariable Long id, @RequestBody ReportTemplate template) {
        return ApiResponse.success("Report updated", reportService.updateReport(id, template));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteReport(@PathVariable Long id) {
        reportService.deleteReport(id);
        return ApiResponse.success("Report deleted", null);
    }
}
