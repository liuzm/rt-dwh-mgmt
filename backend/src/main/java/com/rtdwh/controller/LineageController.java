package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.LineageGraphDTO;
import com.rtdwh.service.LineageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/lineage")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('lineage:view')")
public class LineageController {
    private final LineageService lineageService;
    private final com.rtdwh.util.SecurityContextUtil securityContextUtil;

    @GetMapping("/graph")
    public ApiResponse<LineageGraphDTO> getGraph(
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(lineageService.getGraph(layer, keyword, securityContextUtil.getCurrentUserId()));
    }
}
