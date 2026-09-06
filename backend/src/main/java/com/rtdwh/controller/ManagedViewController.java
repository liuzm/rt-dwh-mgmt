package com.rtdwh.controller;
import com.rtdwh.dto.ApiResponse;
import com.rtdwh.service.ManagedViewService;
import com.rtdwh.util.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/dwh/views") @RequiredArgsConstructor
@PreAuthorize("hasAuthority('dwh:view')")
public class ManagedViewController {
 private final ManagedViewService service;
 private final SecurityContextUtil security;
 @PostMapping @PreAuthorize("hasAuthority('dwh:manage')")
 public ApiResponse<ManagedViewService.Detail> create(@RequestBody ManagedViewService.Draft body){return ApiResponse.success(service.create(body,security.getCurrentUserId()));}
 @GetMapping("/{assetId}") public ApiResponse<ManagedViewService.Detail> detail(@PathVariable String assetId){return ApiResponse.success(service.detail(assetId,security.getCurrentUserId()));}
 @PutMapping("/{assetId}") @PreAuthorize("hasAuthority('dwh:manage')")
 public ApiResponse<ManagedViewService.Detail> save(@PathVariable String assetId,@RequestBody ManagedViewService.Draft body){return ApiResponse.success(service.save(assetId,body,security.getCurrentUserId()));}
 @PostMapping("/{assetId}/preview") @PreAuthorize("hasAuthority('dwh:manage')")
 public ApiResponse<ManagedViewService.Preview> preview(@PathVariable String assetId){return ApiResponse.success(service.preview(assetId,security.getCurrentUserId()));}
 @PostMapping("/{assetId}/publish") @PreAuthorize("hasAuthority('dwh:manage')")
 public ApiResponse<ManagedViewService.Detail> publish(@PathVariable String assetId,@RequestBody ManagedViewService.Publication body){return ApiResponse.success(service.publish(assetId,body,security.getCurrentUserId()));}
 @GetMapping("/{assetId}/health") public ApiResponse<java.util.Map<String,Object>> health(@PathVariable String assetId){return ApiResponse.success(service.health(assetId,security.getCurrentUserId()));}
}
