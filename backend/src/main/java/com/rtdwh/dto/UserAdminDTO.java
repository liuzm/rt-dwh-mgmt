package com.rtdwh.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class UserAdminDTO {
    private UserAdminDTO() {}

    public record UserView(Long id, String username, String realName, String email, String phone,
                           String status, Set<RoleSummary> roles,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record RoleSummary(Long id, String roleCode, String roleName) {}
    public record RoleView(Long id, String roleCode, String roleName, String description,
                           Set<PermissionView> permissions, List<DataScopeView> dataScopes) {}
    public record PermissionView(Long id, String permCode, String permName, String resourceType) {}
    public record DataScopeView(Long id, String catalogPattern, String databasePattern, String tablePattern) {}

    @Data
    public static class CreateUserRequest {
        @NotBlank @Size(min = 3, max = 64) private String username;
        @NotBlank @Size(min = 8, max = 128) private String password;
        @Size(max = 64) private String realName;
        @Email @Size(max = 128) private String email;
        @Size(max = 20) private String phone;
        @NotEmpty private Set<Long> roleIds;
    }

    @Data
    public static class UpdateUserRequest {
        @Size(max = 64) private String realName;
        @Email @Size(max = 128) private String email;
        @Size(max = 20) private String phone;
        @NotEmpty private Set<Long> roleIds;
    }

    @Data
    public static class ResetPasswordRequest {
        @NotBlank @Size(min = 8, max = 128) private String password;
    }

    @Data
    public static class RoleRequest {
        @NotBlank @Size(min = 2, max = 32) private String roleCode;
        @NotBlank @Size(max = 64) private String roleName;
        @Size(max = 256) private String description;
        private Set<Long> permissionIds;
        private List<DataScopeRequest> dataScopes;
    }

    @Data
    public static class DataScopeRequest {
        @NotBlank @Size(max = 128) private String catalogPattern;
        @NotBlank @Size(max = 128) private String databasePattern;
        @NotBlank @Size(max = 128) private String tablePattern;
    }
}
