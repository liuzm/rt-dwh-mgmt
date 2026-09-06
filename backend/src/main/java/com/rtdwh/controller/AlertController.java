package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.AlertRule;
import com.rtdwh.entity.AlertRecord;
import com.rtdwh.service.AlertNotifyService;
import com.rtdwh.service.AlertEvaluationService;
import com.rtdwh.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/alert")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('alert:view')")
public class AlertController {

    private final AlertService alertService;
    private final AlertNotifyService alertNotifyService;
    private final AlertEvaluationService alertEvaluationService;

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> getRules() {
        return ApiResponse.success(alertService.listRules());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<AlertRule> createRule(@RequestBody AlertRule rule) {
        return ApiResponse.success("规则创建成功", alertService.createRule(rule));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRule rule) {
        return ApiResponse.success("规则已更新", alertService.updateRule(id, rule));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        alertService.deleteRule(id);
        return ApiResponse.success("规则已删除", null);
    }

    @PostMapping("/rules/{id}/toggle")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<Void> toggleRule(@PathVariable Long id) {
        alertService.toggleRule(id);
        return ApiResponse.success("状态已切换", null);
    }

    @GetMapping("/records")
    public ApiResponse<List<AlertRecord>> getRecords(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean resolved) {
        return ApiResponse.success(alertService.listRecords(level, resolved));
    }

    @PostMapping("/records/{id}/resolve")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<Void> resolveRecord(@PathVariable Long id) {
        alertService.resolveRecord(id);
        return ApiResponse.success("已标记为解决", null);
    }

    @PostMapping("/test-notify")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<Void> testNotify(@RequestBody AlertRule rule) {
        alertNotifyService.sendAlert(rule, "这是一条测试告警消息", "info");
        return ApiResponse.success("测试通知已发送", null);
    }

    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<AlertEvaluationService.EvaluationSummary> evaluate() {
        return ApiResponse.success("告警规则评估完成", alertEvaluationService.evaluateAll());
    }

    @PostMapping("/rules/{id}/evaluate")
    @PreAuthorize("hasAuthority('alert:manage')")
    public ApiResponse<AlertEvaluationService.EvaluationSummary> evaluateRule(@PathVariable Long id) {
        return ApiResponse.success("告警规则评估完成", alertEvaluationService.evaluateRule(id));
    }
}
