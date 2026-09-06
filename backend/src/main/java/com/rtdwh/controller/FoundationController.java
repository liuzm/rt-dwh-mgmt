package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.FoundationDTO;
import com.rtdwh.service.FoundationService;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/foundation")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('foundation:view')")
public class FoundationController {
    private final FoundationService service;
    private final SecurityContextUtil security;

    @GetMapping("/summary")
    public ApiResponse<FoundationDTO.Summary> summary() {
        return ApiResponse.success(service.summary(security.getCurrentUserId(), security.hasAuthority("report:view"),
                security.hasAuthority("data-service:view"), security.hasAuthority("user:manage"),
                security.hasAuthority("audit:view")));
    }

    @GetMapping("/search")
    public ApiResponse<List<FoundationDTO.SearchItem>> search(@RequestParam String keyword,
                                                              @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(service.search(security.getCurrentUserId(), keyword, limit,
                security.hasAuthority("report:view"), security.hasAuthority("data-service:view")));
    }

    @GetMapping("/sla-risks")
    public ApiResponse<List<FoundationDTO.SlaRisk>> slaRisks() {
        return ApiResponse.success(service.slaRisks(security.getCurrentUserId()));
    }
}
