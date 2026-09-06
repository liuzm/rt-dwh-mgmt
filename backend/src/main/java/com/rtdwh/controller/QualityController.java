package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.QualityCheckSummary;
import com.rtdwh.dto.QualityOverviewSummary;
import com.rtdwh.entity.QualityRule;
import com.rtdwh.entity.QualityAlert;
import com.rtdwh.entity.QualityCheckRun;
import com.rtdwh.service.QualityCheckService;
import com.rtdwh.service.QualityService;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/quality")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('quality:view')")
public class QualityController {

    private final QualityService qualityService;
    private final QualityCheckService qualityCheckService;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping("/rules")
    public ApiResponse<List<QualityRule>> getRules(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String ruleType) {
        return ApiResponse.success(qualityService.listRules(
                layer, ruleType, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/overview")
    public ApiResponse<QualityOverviewSummary> overview(@RequestParam(required = false) java.time.LocalDate businessDate) {
        return ApiResponse.success(qualityCheckService.getOverview(securityContextUtil.getCurrentUserId(), businessDate));
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityRule> createRule(@RequestBody QualityRule rule) {
        return ApiResponse.success("规则创建成功", qualityService.createRule(
                rule, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityCheckService.Preview> preview(@RequestBody QualityRule rule,
            @RequestParam(required = false) java.time.LocalDate businessDate) {
        return ApiResponse.success(qualityCheckService.preview(rule, businessDate, securityContextUtil.getCurrentUserId()));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityRule> updateRule(@PathVariable Long id, @RequestBody QualityRule rule) {
        return ApiResponse.success("规则更新成功", qualityService.updateRule(
                id, rule, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/rules/{id}/toggle")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityRule> toggleRule(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        if (body == null || !body.containsKey("enabled") || body.get("enabled") == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        return ApiResponse.success("规则状态已更新",
                qualityService.setRuleEnabled(id, body.get("enabled"), securityContextUtil.getCurrentUserId()));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        qualityService.deleteRule(id, securityContextUtil.getCurrentUserId());
        return ApiResponse.success("规则已删除", null);
    }

    @PostMapping("/run-check/all")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityCheckSummary> runAllChecks(@RequestParam(required = false) java.time.LocalDate businessDate) {
        QualityCheckSummary summary = qualityCheckService.runAllChecksWithSummary(securityContextUtil.getCurrentUserId(), businessDate);
        return checkResponse(summary);
    }

    @PostMapping("/rules/{id}/run")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityCheckSummary> runRule(@PathVariable Long id, @RequestParam(required = false) java.time.LocalDate businessDate) {
        return checkResponse(qualityCheckService.runCheckWithSummary(
                id, securityContextUtil.getCurrentUserId(), businessDate));
    }

    private ApiResponse<QualityCheckSummary> checkResponse(QualityCheckSummary summary) {
        return ApiResponse.success("质量检查完成：通过 " + summary.passed()
                + " 条，未通过 " + summary.failed() + " 条，执行异常 " + summary.errorCount() + " 条", summary);
    }

    @GetMapping("/alerts")
    public ApiResponse<List<QualityAlert>> getAlerts(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean resolved) {
        return ApiResponse.success(qualityService.listAlerts(
                level, resolved, securityContextUtil.getCurrentUserId()));
    }

    @GetMapping("/runs")
    public ApiResponse<List<QualityCheckRun>> getRuns(@RequestParam(required = false) Long ruleId) {
        return ApiResponse.success(qualityCheckService.listRuns(
                ruleId, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('quality:manage')")
    public ApiResponse<QualityAlert> resolveAlert(@PathVariable Long id) {
        return ApiResponse.success("告警已解决", qualityService.resolveAlert(
                id, securityContextUtil.getCurrentUserId()));
    }
}
