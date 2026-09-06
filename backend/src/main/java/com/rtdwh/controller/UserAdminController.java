package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.UserAdminDTO;
import com.rtdwh.service.UserAdminService;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private final UserAdminService userAdminService;
    private final SecurityContextUtil securityContextUtil;

    @GetMapping("/users")
    public ApiResponse<List<UserAdminDTO.UserView>> users() {
        return ApiResponse.success(userAdminService.users());
    }

    @PostMapping("/users")
    public ApiResponse<UserAdminDTO.UserView> create(@Valid @RequestBody UserAdminDTO.CreateUserRequest request) {
        return ApiResponse.success("用户已创建", userAdminService.create(request));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<UserAdminDTO.UserView> update(@PathVariable Long id,
                                                     @Valid @RequestBody UserAdminDTO.UpdateUserRequest request) {
        return ApiResponse.success("用户已更新", userAdminService.update(id, request));
    }

    @PostMapping("/users/{id}/toggle-status")
    public ApiResponse<UserAdminDTO.UserView> toggleStatus(@PathVariable Long id) {
        return ApiResponse.success("用户状态已更新", userAdminService.toggleStatus(
                id, securityContextUtil.getCurrentUserId()));
    }

    @PostMapping("/users/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                            @Valid @RequestBody UserAdminDTO.ResetPasswordRequest request) {
        userAdminService.resetPassword(id, request.getPassword());
        return ApiResponse.success("密码已重置", null);
    }

    @GetMapping("/roles")
    public ApiResponse<List<UserAdminDTO.RoleView>> roles() {
        return ApiResponse.success(userAdminService.roles());
    }

    @PostMapping("/roles")
    public ApiResponse<UserAdminDTO.RoleView> createRole(@Valid @RequestBody UserAdminDTO.RoleRequest request) {
        return ApiResponse.success("角色已创建", userAdminService.createRole(request));
    }

    @PutMapping("/roles/{id}")
    public ApiResponse<UserAdminDTO.RoleView> updateRole(@PathVariable Long id,
                                                         @Valid @RequestBody UserAdminDTO.RoleRequest request) {
        return ApiResponse.success("角色已更新", userAdminService.updateRole(id, request));
    }

    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        userAdminService.deleteRole(id);
        return ApiResponse.success("角色已删除", null);
    }

    @GetMapping("/permissions")
    public ApiResponse<List<UserAdminDTO.PermissionView>> permissions() {
        return ApiResponse.success(userAdminService.permissions());
    }
}
