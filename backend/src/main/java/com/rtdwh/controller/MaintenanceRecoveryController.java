package com.rtdwh.controller;

import com.rtdwh.dto.*;
import com.rtdwh.service.PaimonMaintenanceService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/dwh/maintenance") @RequiredArgsConstructor
@PreAuthorize("hasAuthority('dwh:view')")
public class MaintenanceRecoveryController {
    private final PaimonMaintenanceService maintenance;
    private final SecurityContextUtil security;

    @GetMapping("/{id}")
    public ApiResponse<PaimonMaintenanceService.Detail> detail(@PathVariable Long id) {
        return ApiResponse.success(maintenance.detail(id, security.getCurrentUserId()));
    }
    @PostMapping("/{id}/recovery") @PreAuthorize("hasAuthority('dwh:manage')")
    public ApiResponse<PaimonMaintenanceService.Detail> recover(@PathVariable Long id, @Valid @RequestBody MaintenanceRecoveryDTO request) {
        return ApiResponse.success("恢复请求已处理，请依据最新证据查看结果", maintenance.recover(id, request, security.getCurrentUserId()));
    }
}
