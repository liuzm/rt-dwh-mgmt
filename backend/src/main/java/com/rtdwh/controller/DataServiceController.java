package com.rtdwh.controller;

import com.rtdwh.dto.*;
import com.rtdwh.entity.*;
import com.rtdwh.service.DataServiceService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController @RequestMapping("/data-services") @RequiredArgsConstructor
@PreAuthorize("hasAuthority('data-service:view')")
public class DataServiceController {
    private final DataServiceService service;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping public ApiResponse<List<DataServiceDefinition>> definitions() { return ApiResponse.success(service.definitions(securityContextUtil.getCurrentUserId())); }
    @GetMapping("/{id}") public ApiResponse<DataServiceDefinition> definition(@PathVariable Long id) {
        return ApiResponse.success(service.definition(id, securityContextUtil.getCurrentUserId()));
    }
    @GetMapping("/{id}/versions") public ApiResponse<List<DataServiceVersion>> versions(@PathVariable Long id) {
        return ApiResponse.success(service.versions(id, securityContextUtil.getCurrentUserId()));
    }
    @GetMapping("/{id}/published") public ApiResponse<DataServiceVersion> publishedVersion(@PathVariable Long id) {
        return ApiResponse.success(service.publishedVersion(id, securityContextUtil.getCurrentUserId()));
    }
    @PostMapping("/{id}/preview") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDTO.PublicationPreview> preview(@PathVariable Long id, @Valid @RequestBody DataServiceDTO.PublicationRequest request) {
        return ApiResponse.success(service.preview(id, securityContextUtil.getCurrentUserId(), request));
    }
    @PostMapping @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDefinition> create(@Valid @RequestBody DataServiceDTO.DefinitionRequest request) {
        return ApiResponse.success("数据服务已创建", service.createDefinition(request, securityContextUtil.getCurrentUserId()));
    }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDefinition> update(@PathVariable Long id, @Valid @RequestBody DataServiceDTO.DefinitionRequest request) {
        return ApiResponse.success("草稿已保存，线上版本保持不变", service.updateDefinition(id, request, securityContextUtil.getCurrentUserId()));
    }
    @PostMapping("/{id}/publish") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDefinition> publish(@PathVariable Long id, @RequestParam(defaultValue="true") boolean published,
                                                    @Valid @RequestBody DataServiceDTO.PublicationRequest request) {
        return ApiResponse.success(published ? "数据服务已发布" : "数据服务已下线", service.publish(id, published, securityContextUtil.getCurrentUserId(), request));
    }
    @PostMapping("/{id}/rollback/{versionId}") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDefinition> rollback(@PathVariable Long id, @PathVariable Long versionId,
                                                      @Valid @RequestBody DataServiceDTO.PublicationRequest request) {
        return ApiResponse.success("已校验并重新发布所选版本，草稿保持不变", service.rollback(id, versionId, securityContextUtil.getCurrentUserId(), request));
    }
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id) { service.deleteDefinition(id, securityContextUtil.getCurrentUserId()); return ApiResponse.success("数据服务已删除", null); }

    @GetMapping("/apps") public ApiResponse<List<DataServiceApp>> apps() { return ApiResponse.success(service.apps(securityContextUtil.getCurrentUserId())); }
    @PostMapping("/apps") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDTO.AppCredential> createApp(@Valid @RequestBody DataServiceDTO.AppRequest request) {
        return ApiResponse.success("应用已创建，请立即保存密钥", service.createApp(request, securityContextUtil.getCurrentUserId()));
    }
    @PostMapping("/apps/{appId}/rotate-secret") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceDTO.AppCredential> rotate(@PathVariable Long appId) { return ApiResponse.success("密钥已轮换", service.rotateSecret(appId, securityContextUtil.getCurrentUserId())); }
    @PostMapping("/apps/{appId}/toggle") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceApp> toggle(@PathVariable Long appId) { return ApiResponse.success("应用状态已更新", service.toggleApp(appId, securityContextUtil.getCurrentUserId())); }
    @GetMapping("/apps/{appId}/grants") public ApiResponse<List<DataServiceGrant>> grants(@PathVariable Long appId) { return ApiResponse.success(service.grants(appId, securityContextUtil.getCurrentUserId())); }
    @PostMapping("/apps/{appId}/grants") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<DataServiceGrant> grant(@PathVariable Long appId, @Valid @RequestBody DataServiceDTO.GrantRequest request) {
        return ApiResponse.success("服务授权已添加", service.grant(appId, request.getServiceId(), securityContextUtil.getCurrentUserId()));
    }
    @DeleteMapping("/apps/{appId}/grants/{serviceId}") @PreAuthorize("hasAuthority('data-service:manage')")
    public ApiResponse<Void> revoke(@PathVariable Long appId, @PathVariable Long serviceId) { service.revoke(appId, serviceId, securityContextUtil.getCurrentUserId()); return ApiResponse.success("服务授权已移除", null); }
    @GetMapping("/logs") public ApiResponse<List<DataServiceInvocationLog>> logs(@RequestParam(defaultValue="100") int limit) { return ApiResponse.success(service.logs(limit, securityContextUtil.getCurrentUserId())); }
}
