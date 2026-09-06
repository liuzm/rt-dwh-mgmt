package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.service.DataServiceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController @RequestMapping("/open/data") @RequiredArgsConstructor
public class OpenDataServiceController {
    private final DataServiceService service;

    @PostMapping("/{serviceCode}")
    public ApiResponse<Map<String, Object>> invoke(
            @PathVariable String serviceCode,
            @RequestHeader(value="X-App-Key", required=false) String appKey,
            @RequestHeader(value="X-App-Secret", required=false) String appSecret,
            @RequestBody(required=false) Map<String, Object> parameters,
            HttpServletRequest request) {
        return ApiResponse.success(service.invoke(serviceCode, appKey, appSecret,
                parameters == null ? Map.of() : parameters, clientIp(request)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        return request.getRemoteAddr();
    }
}
